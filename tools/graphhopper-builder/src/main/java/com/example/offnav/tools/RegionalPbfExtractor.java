package com.example.offnav.tools;

import com.carrotsearch.hppc.LongHashSet;
import com.google.protobuf.ByteString;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMInputFile;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import org.openstreetmap.osmosis.osmbinary.file.BlockOutputStream;
import org.openstreetmap.osmosis.osmbinary.file.FileBlock;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Produces a bounded OSM PBF without requiring an external osmium/osmosis executable.
 *
 * <p>The extractor uses a complete-ways strategy: any way touching an in-bounds node is
 * retained, all of that way's nodes are retained, and relations touching retained members
 * are clipped to available members. Turn restrictions pull in their small set of remaining
 * members. This preserves routing continuity at the regional boundary without allowing route
 * or administrative super-relations to expand the extract across the state.</p>
 */
public final class RegionalPbfExtractor {
    private static final long PROGRESS_INTERVAL = 2_000_000L;
    private static final int MAX_RELATION_EXPANSION_PASSES = 8;

    private RegionalPbfExtractor() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        Path input = requiredPath(options, "--input");
        Path output = requiredPath(options, "--output");
        String region = options.getOrDefault("--region", output.getFileName().toString());
        Bounds bounds = new Bounds(
                requiredDouble(options, "--min-lat"),
                requiredDouble(options, "--max-lat"),
                requiredDouble(options, "--min-lon"),
                requiredDouble(options, "--max-lon")
        );

        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("OSM PBF does not exist: " + input);
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());

        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        Instant started = Instant.now();
        Selection selection = new Selection();

        System.out.printf(
                Locale.ROOT,
                "Selecting %s from %s (%.1f MB)%nBounds: %.4f,%.4f to %.4f,%.4f%n",
                region,
                input,
                Files.size(input) / 1_000_000.0,
                bounds.minLat,
                bounds.minLon,
                bounds.maxLat,
                bounds.maxLon
        );

        selectNodesWaysAndRelations(input, bounds, selection, started);
        expandRelations(input, selection, started);
        collectCompleteWayNodesAndPruneUnavailable(input, selection, started);

        try {
            Counts written = writeExtract(input, partial, bounds, selection, started);
            if (written.nodes == 0 || written.ways == 0) {
                throw new IllegalStateException("Regional extract contains no routable OSM data");
            }
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
            System.out.printf(
                    Locale.ROOT,
                    "Ready: %s (%.1f MB, %,d nodes, %,d ways, %,d relations, elapsed %s)%n",
                    output,
                    Files.size(output) / 1_000_000.0,
                    written.nodes,
                    written.ways,
                    written.relations,
                    formatDuration(Duration.between(started, Instant.now()))
            );
        } catch (Throwable failure) {
            Files.deleteIfExists(partial);
            throw failure;
        }
    }

    private static void selectNodesWaysAndRelations(
            Path input,
            Bounds bounds,
            Selection selection,
            Instant started
    ) throws Exception {
        System.out.println("Pass 1: selecting in-bounds nodes, touching ways, and relations");
        long elements = 0;
        try (OSMInputFile osm = open(input)) {
            ReaderElement element;
            while ((element = osm.getNext()) != null) {
                elements++;
                switch (element.getType()) {
                    case NODE -> {
                        ReaderNode node = (ReaderNode) element;
                        if (bounds.contains(node.getLat(), node.getLon())) {
                            selection.nodes.add(node.getId());
                        }
                    }
                    case WAY -> {
                        ReaderWay way = (ReaderWay) element;
                        if (touchesSelectedNode(way, selection.nodes)) {
                            selection.ways.add(way.getId());
                        }
                    }
                    case RELATION -> selectRelation((ReaderRelation) element, selection);
                    default -> {
                    }
                }
                logProgress(elements, selection, started);
            }
        }
        printSelection(selection);
    }

    private static void expandRelations(Path input, Selection selection, Instant started) throws Exception {
        for (int pass = 1; pass <= MAX_RELATION_EXPANSION_PASSES; pass++) {
            int relationsBefore = selection.relations.size();
            int waysBefore = selection.ways.size();
            int nodesBefore = selection.nodes.size();
            System.out.printf(Locale.ROOT, "Relation expansion pass %d%n", pass);

            try (OSMInputFile osm = open(input)) {
                ReaderElement element;
                while ((element = osm.getNext()) != null) {
                    if (element.getType() == ReaderElement.Type.RELATION) {
                        selectRelation((ReaderRelation) element, selection);
                    }
                }
            }

            boolean changed = relationsBefore != selection.relations.size()
                    || waysBefore != selection.ways.size()
                    || nodesBefore != selection.nodes.size();
            printSelection(selection);
            if (!changed) return;
        }
        throw new IllegalStateException("Relation selection did not stabilize after "
                + MAX_RELATION_EXPANSION_PASSES + " passes");
    }

    private static void collectCompleteWayNodesAndPruneUnavailable(
            Path input,
            Selection selection,
            Instant started
    ) throws Exception {
        System.out.println("Collecting complete-way nodes and pruning unavailable members");
        long elements = 0;
        LongHashSet availableWays = new LongHashSet(selection.ways.size());
        LongHashSet availableRelations = new LongHashSet(selection.relations.size());
        try (OSMInputFile osm = open(input)) {
            ReaderElement element;
            while ((element = osm.getNext()) != null) {
                elements++;
                if (element.getType() == ReaderElement.Type.WAY
                        && selection.ways.contains(element.getId())) {
                    ReaderWay way = (ReaderWay) element;
                    availableWays.add(way.getId());
                    for (int index = 0; index < way.getNodes().size(); index++) {
                        selection.nodes.add(way.getNodes().get(index));
                    }
                } else if (element.getType() == ReaderElement.Type.RELATION
                        && selection.relations.contains(element.getId())) {
                    availableRelations.add(element.getId());
                }
                logProgress(elements, selection, started);
            }
        }
        selection.ways = availableWays;
        selection.relations = availableRelations;
        printSelection(selection);
    }

    private static Counts writeExtract(
            Path input,
            Path output,
            Bounds bounds,
            Selection selection,
            Instant started
    ) throws Exception {
        System.out.println("Writing regional PBF");
        Counts counts = new Counts();
        LongHashSet availableNodes = new LongHashSet(selection.nodes.size());
        boolean nodeSelectionFinalized = false;
        try (PbfWriter writer = new PbfWriter(output, bounds, selection);
             OSMInputFile osm = open(input)) {
            ReaderElement element;
            long elements = 0;
            while ((element = osm.getNext()) != null) {
                elements++;
                if (!nodeSelectionFinalized
                        && (element.getType() == ReaderElement.Type.WAY
                        || element.getType() == ReaderElement.Type.RELATION)) {
                    selection.nodes = availableNodes;
                    nodeSelectionFinalized = true;
                }
                boolean selected = switch (element.getType()) {
                    case NODE -> {
                        boolean keep = selection.nodes.contains(element.getId());
                        if (keep) availableNodes.add(element.getId());
                        yield keep;
                    }
                    case WAY -> {
                        boolean keep = selection.ways.contains(element.getId())
                                && hasEveryNode((ReaderWay) element, selection.nodes);
                        if (!keep) selection.ways.remove(element.getId());
                        yield keep;
                    }
                    case RELATION -> {
                        ReaderRelation relation = (ReaderRelation) element;
                        boolean keep = selection.relations.contains(element.getId())
                                && hasAvailableMember(relation, selection);
                        if (!keep) selection.relations.remove(element.getId());
                        yield keep;
                    }
                    default -> false;
                };
                if (selected) {
                    writer.write(element);
                    switch (element.getType()) {
                        case NODE -> counts.nodes++;
                        case WAY -> counts.ways++;
                        case RELATION -> counts.relations++;
                        default -> {
                        }
                    }
                }
                if (elements % PROGRESS_INTERVAL == 0) {
                    System.out.printf(
                            Locale.ROOT,
                            "Read %,d elements; wrote %,d nodes, %,d ways, %,d relations; elapsed %s%n",
                            elements,
                            counts.nodes,
                            counts.ways,
                            counts.relations,
                            formatDuration(Duration.between(started, Instant.now()))
                    );
                }
            }
            if (!nodeSelectionFinalized) selection.nodes = availableNodes;
        }
        return counts;
    }

    private static boolean hasEveryNode(ReaderWay way, LongHashSet nodes) {
        for (int index = 0; index < way.getNodes().size(); index++) {
            if (!nodes.contains(way.getNodes().get(index))) return false;
        }
        return true;
    }

    private static boolean selectRelation(ReaderRelation relation, Selection selection) {
        if (selection.relations.contains(relation.getId())) return false;

        boolean touches = false;
        for (ReaderRelation.Member member : relation.getMembers()) {
            touches |= switch (member.getType()) {
                case NODE -> selection.nodes.contains(member.getRef());
                case WAY -> selection.ways.contains(member.getRef());
                case RELATION -> false;
                default -> false;
            };
            if (touches) break;
        }
        if (!touches) return false;

        selection.relations.add(relation.getId());
        if (requiresCompleteRoutingMembers(relation)) addRoutingMembers(relation, selection);
        return true;
    }

    private static boolean requiresCompleteRoutingMembers(ReaderRelation relation) {
        String type = String.valueOf(relation.getTags().get("type"));
        return type.equals("restriction")
                || type.startsWith("restriction:");
    }

    private static void addRoutingMembers(ReaderRelation relation, Selection selection) {
        for (ReaderRelation.Member member : relation.getMembers()) {
            switch (member.getType()) {
                case NODE -> selection.nodes.add(member.getRef());
                case WAY -> selection.ways.add(member.getRef());
                default -> {
                }
            }
        }
    }

    private static boolean hasAvailableMember(ReaderRelation relation, Selection selection) {
        for (ReaderRelation.Member member : relation.getMembers()) {
            boolean available = switch (member.getType()) {
                case NODE -> selection.nodes.contains(member.getRef());
                case WAY -> selection.ways.contains(member.getRef());
                case RELATION -> selection.relations.contains(member.getRef());
                default -> false;
            };
            if (available) return true;
        }
        return false;
    }

    private static boolean touchesSelectedNode(ReaderWay way, LongHashSet nodes) {
        for (int index = 0; index < way.getNodes().size(); index++) {
            if (nodes.contains(way.getNodes().get(index))) return true;
        }
        return false;
    }

    private static OSMInputFile open(Path input) throws Exception {
        return new OSMInputFile(input.toFile())
                .setWorkerThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))
                .open();
    }

    private static void logProgress(long elements, Selection selection, Instant started) {
        if (elements % PROGRESS_INTERVAL != 0) return;
        System.out.printf(
                Locale.ROOT,
                "Read %,d elements; selected %,d nodes, %,d ways, %,d relations; elapsed %s%n",
                elements,
                selection.nodes.size(),
                selection.ways.size(),
                selection.relations.size(),
                formatDuration(Duration.between(started, Instant.now()))
        );
    }

    private static void printSelection(Selection selection) {
        System.out.printf(
                Locale.ROOT,
                "Selection: %,d nodes, %,d ways, %,d relations%n",
                selection.nodes.size(),
                selection.ways.size(),
                selection.relations.size()
        );
    }

    private static Map<String, String> parseOptions(String[] args) {
        if (args.length == 0 || args.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Usage: --input <source.osm.pbf> --output <region.osm.pbf> --region <name> "
                            + "--min-lat N --max-lat N --min-lon N --max-lon N"
            );
        }
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (options.put(args[index], args[index + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option: " + args[index]);
            }
        }
        return options;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static double requiredDouble(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return Double.parseDouble(value);
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        return minutes > 0 ? minutes + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    private record Bounds(double minLat, double maxLat, double minLon, double maxLon) {
        private Bounds {
            if (!Double.isFinite(minLat) || !Double.isFinite(maxLat)
                    || !Double.isFinite(minLon) || !Double.isFinite(maxLon)
                    || minLat < -90 || maxLat > 90 || minLon < -180 || maxLon > 180
                    || minLat >= maxLat || minLon >= maxLon) {
                throw new IllegalArgumentException("Invalid regional bounds");
            }
        }

        private boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
    }

    private static final class Selection {
        private LongHashSet nodes = new LongHashSet();
        private LongHashSet ways = new LongHashSet();
        private LongHashSet relations = new LongHashSet();
    }

    private static final class Counts {
        private long nodes;
        private long ways;
        private long relations;
    }

    private static final class PbfWriter implements AutoCloseable {
        private static final int GRANULARITY = 100;
        private static final int BATCH_LIMIT = 8_000;

        private final BlockOutputStream output;
        private final List<ReaderElement> batch = new ArrayList<>(BATCH_LIMIT);
        private final Selection selection;
        private ReaderElement.Type batchType;

        private PbfWriter(Path path, Bounds bounds, Selection selection) throws IOException {
            this.selection = selection;
            output = new BlockOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
            output.setCompress("deflate");
            Osmformat.HeaderBBox bbox = Osmformat.HeaderBBox.newBuilder()
                    .setLeft(toNano(bounds.minLon))
                    .setRight(toNano(bounds.maxLon))
                    .setBottom(toNano(bounds.minLat))
                    .setTop(toNano(bounds.maxLat))
                    .build();
            Osmformat.HeaderBlock header = Osmformat.HeaderBlock.newBuilder()
                    .setBbox(bbox)
                    .addRequiredFeatures("OsmSchema-V0.6")
                    .setSource("OffNav RegionalPbfExtractor")
                    .build();
            output.write(FileBlock.newInstance("OSMHeader", header.toByteString(), null));
        }

        private void write(ReaderElement element) throws IOException {
            if (batchType != null && batchType != element.getType()) flushBatch();
            batchType = element.getType();
            batch.add(element);
            int limit = batchType == ReaderElement.Type.RELATION ? 256 : BATCH_LIMIT;
            if (batch.size() >= limit) flushBatch();
        }

        private void flushBatch() throws IOException {
            if (batch.isEmpty()) return;
            LinkedHashMap<String, Integer> strings = new LinkedHashMap<>();
            strings.put("", 0);
            for (ReaderElement element : batch) {
                for (Map.Entry<String, Object> tag : element.getTags().entrySet()) {
                    stringIndex(strings, tag.getKey());
                    stringIndex(strings, String.valueOf(tag.getValue()));
                }
                if (element instanceof ReaderRelation relation) {
                    for (ReaderRelation.Member member : relation.getMembers()) {
                        stringIndex(strings, member.getRole() == null ? "" : member.getRole());
                    }
                }
            }

            Osmformat.StringTable.Builder table = Osmformat.StringTable.newBuilder();
            for (String value : strings.keySet()) table.addS(ByteString.copyFromUtf8(value));

            Osmformat.PrimitiveGroup.Builder group = Osmformat.PrimitiveGroup.newBuilder();
            for (ReaderElement element : batch) {
                switch (element.getType()) {
                    case NODE -> group.addNodes(node((ReaderNode) element, strings));
                    case WAY -> group.addWays(way((ReaderWay) element, strings));
                    case RELATION -> group.addRelations(relation((ReaderRelation) element, strings, selection));
                    default -> throw new IllegalArgumentException("Unsupported PBF element: " + element.getType());
                }
            }

            Osmformat.PrimitiveBlock block = Osmformat.PrimitiveBlock.newBuilder()
                    .setStringtable(table)
                    .setGranularity(GRANULARITY)
                    .addPrimitivegroup(group)
                    .build();
            output.write(FileBlock.newInstance("OSMData", block.toByteString(), null));
            batch.clear();
            batchType = null;
        }

        private static Osmformat.Node node(ReaderNode source, Map<String, Integer> strings) {
            Osmformat.Node.Builder target = Osmformat.Node.newBuilder()
                    .setId(source.getId())
                    .setLat(toCoordinate(source.getLat()))
                    .setLon(toCoordinate(source.getLon()));
            addTags(source, strings, target::addKeys, target::addVals);
            return target.build();
        }

        private static Osmformat.Way way(ReaderWay source, Map<String, Integer> strings) {
            Osmformat.Way.Builder target = Osmformat.Way.newBuilder().setId(source.getId());
            addTags(source, strings, target::addKeys, target::addVals);
            long previous = 0;
            for (int index = 0; index < source.getNodes().size(); index++) {
                long id = source.getNodes().get(index);
                target.addRefs(id - previous);
                previous = id;
            }
            return target.build();
        }

        private static Osmformat.Relation relation(
                ReaderRelation source,
                Map<String, Integer> strings,
                Selection selection
        ) {
            Osmformat.Relation.Builder target = Osmformat.Relation.newBuilder().setId(source.getId());
            addTags(source, strings, target::addKeys, target::addVals);
            long previous = 0;
            for (ReaderRelation.Member member : source.getMembers()) {
                boolean available = switch (member.getType()) {
                    case NODE -> selection.nodes.contains(member.getRef());
                    case WAY -> selection.ways.contains(member.getRef());
                    case RELATION -> selection.relations.contains(member.getRef());
                    default -> false;
                };
                if (!available) continue;
                target.addRolesSid(strings.get(member.getRole() == null ? "" : member.getRole()));
                target.addMemids(member.getRef() - previous);
                previous = member.getRef();
                target.addTypes(switch (member.getType()) {
                    case NODE -> Osmformat.Relation.MemberType.NODE;
                    case WAY -> Osmformat.Relation.MemberType.WAY;
                    case RELATION -> Osmformat.Relation.MemberType.RELATION;
                    default -> throw new IllegalArgumentException("Unsupported relation member type");
                });
            }
            return target.build();
        }

        private static void addTags(
                ReaderElement source,
                Map<String, Integer> strings,
                IntSink keys,
                IntSink values
        ) {
            for (Map.Entry<String, Object> tag : source.getTags().entrySet()) {
                keys.add(strings.get(tag.getKey()));
                values.add(strings.get(String.valueOf(tag.getValue())));
            }
        }

        private static int stringIndex(Map<String, Integer> strings, String value) {
            Integer existing = strings.get(value);
            if (existing != null) return existing;
            int index = strings.size();
            strings.put(value, index);
            return index;
        }

        private static long toCoordinate(double degrees) {
            return Math.round(degrees * 1_000_000_000.0 / GRANULARITY);
        }

        private static long toNano(double degrees) {
            return Math.round(degrees * 1_000_000_000.0);
        }

        @Override
        public void close() throws IOException {
            flushBatch();
            output.close();
        }

        @FunctionalInterface
        private interface IntSink {
            void add(int value);
        }
    }
}
