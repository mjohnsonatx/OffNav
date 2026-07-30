package com.example.offnav.tools;

import com.carrotsearch.hppc.LongLongHashMap;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMInputFile;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Streams a regional OSM PBF and emits search candidates inside an Austin bounding box.
 *
 * The extractor keeps only in-bounds node coordinates. This is enough to calculate useful
 * centers for address/POI ways and relations without retaining the full Texas node set.
 */
public final class AustinSearchExtractor {
    private static final double DEFAULT_MIN_LAT = 30.0980;
    private static final double DEFAULT_MAX_LAT = 30.5160;
    private static final double DEFAULT_MIN_LON = -97.9380;
    private static final double DEFAULT_MAX_LON = -97.5610;
    private static final long PROGRESS_INTERVAL = 2_000_000L;

    private AustinSearchExtractor() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = GraphBuilderOptions.parse(args);
        Path input = GraphBuilderOptions.requiredPath(options, "--input");
        Path output = GraphBuilderOptions.requiredPath(options, "--output");
        Bounds bounds = new Bounds(
                optionDouble(options, "--min-lat", DEFAULT_MIN_LAT),
                optionDouble(options, "--max-lat", DEFAULT_MAX_LAT),
                optionDouble(options, "--min-lon", DEFAULT_MIN_LON),
                optionDouble(options, "--max-lon", DEFAULT_MAX_LON)
        );

        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("OSM PBF does not exist: " + input);
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());

        Instant started = Instant.now();
        LongLongHashMap nodeCoordinates = new LongLongHashMap();
        LongLongHashMap wayCenters = new LongLongHashMap();
        Counters counters = new Counters();

        System.out.printf(
                Locale.ROOT,
                "Extracting Austin search data from %s (%.1f MB)%nBounds: %.4f,%.4f to %.4f,%.4f%n",
                input,
                Files.size(input) / 1_000_000.0,
                bounds.minLat,
                bounds.minLon,
                bounds.maxLat,
                bounds.maxLon
        );

        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        try (BufferedWriter writer = Files.newBufferedWriter(
                partial,
                StandardCharsets.UTF_8
        ); OSMInputFile osm = new OSMInputFile(input.toFile()).setWorkerThreads(
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
        ).open()) {
            writer.write("osm_type\tosm_id\tname\tsubtitle\tcategory\tlatitude\tlongitude\tsearch_text\trank");
            writer.newLine();

            ReaderElement element;
            while ((element = osm.getNext()) != null) {
                counters.elements++;
                switch (element.getType()) {
                    case NODE -> processNode((ReaderNode) element, bounds, nodeCoordinates, writer, counters);
                    case WAY -> processWay((ReaderWay) element, bounds, nodeCoordinates, wayCenters, writer, counters);
                    case RELATION -> processRelation(
                            (ReaderRelation) element,
                            bounds,
                            wayCenters,
                            writer,
                            counters
                    );
                    default -> {
                    }
                }
                if (counters.elements % PROGRESS_INTERVAL == 0) {
                    System.out.printf(
                            Locale.ROOT,
                            "Read %,d elements; %,d Austin nodes; %,d candidates; elapsed %s%n",
                            counters.elements,
                            nodeCoordinates.size(),
                            counters.candidates,
                            formatDuration(Duration.between(started, Instant.now()))
                    );
                }
            }
        } catch (Throwable failure) {
            Files.deleteIfExists(partial);
            throw failure;
        }

        Files.move(partial, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.printf(
                Locale.ROOT,
                "Extracted %,d candidates from %,d elements to %s (elapsed %s)%n",
                counters.candidates,
                counters.elements,
                output,
                formatDuration(Duration.between(started, Instant.now()))
        );
    }

    private static void processNode(
            ReaderNode node,
            Bounds bounds,
            LongLongHashMap nodeCoordinates,
            BufferedWriter writer,
            Counters counters
    ) throws Exception {
        if (!bounds.contains(node.getLat(), node.getLon())) return;
        nodeCoordinates.put(node.getId(), pack(node.getLat(), node.getLon()));
        emitCandidate("node", node, node.getLat(), node.getLon(), writer, counters);
    }

    private static void processWay(
            ReaderWay way,
            Bounds bounds,
            LongLongHashMap nodeCoordinates,
            LongLongHashMap wayCenters,
            BufferedWriter writer,
            Counters counters
    ) throws Exception {
        double lat = 0.0;
        double lon = 0.0;
        int count = 0;
        for (int index = 0; index < way.getNodes().size(); index++) {
            long nodeId = way.getNodes().get(index);
            if (!nodeCoordinates.containsKey(nodeId)) continue;
            long packed = nodeCoordinates.get(nodeId);
            lat += unpackLat(packed);
            lon += unpackLon(packed);
            count++;
        }
        if (count == 0) return;
        lat /= count;
        lon /= count;
        if (!bounds.contains(lat, lon)) return;
        wayCenters.put(way.getId(), pack(lat, lon));
        emitCandidate("way", way, lat, lon, writer, counters);
    }

    private static void processRelation(
            ReaderRelation relation,
            Bounds bounds,
            LongLongHashMap wayCenters,
            BufferedWriter writer,
            Counters counters
    ) throws Exception {
        double lat = 0.0;
        double lon = 0.0;
        int count = 0;
        for (ReaderRelation.Member member : relation.getMembers()) {
            if (member.getType() != ReaderElement.Type.WAY || !wayCenters.containsKey(member.getRef())) {
                continue;
            }
            long packed = wayCenters.get(member.getRef());
            lat += unpackLat(packed);
            lon += unpackLon(packed);
            count++;
        }
        if (count == 0) return;
        lat /= count;
        lon /= count;
        if (!bounds.contains(lat, lon)) return;
        emitCandidate("relation", relation, lat, lon, writer, counters);
    }

    private static void emitCandidate(
            String osmType,
            ReaderElement element,
            double lat,
            double lon,
            BufferedWriter writer,
            Counters counters
    ) throws Exception {
        if (isExplicitlyOutsideAustin(element)) return;
        Classification classification = classify(element);
        String address = formattedAddress(element);
        String displayName;
        String subtitle;

        if (classification != null) {
            displayName = firstNonBlank(
                    element.getTag("name:en"),
                    element.getTag("name"),
                    element.getTag("brand"),
                    element.getTag("operator")
            );
            if (displayName.isBlank()) return;
            subtitle = address.isBlank()
                    ? classification.category
                    : classification.category + " - " + address;
        } else if (!address.isBlank()) {
            classification = new Classification("Address", 100);
            displayName = address;
            subtitle = locality(element);
        } else {
            return;
        }

        List<String> searchParts = new ArrayList<>();
        searchParts.add(displayName);
        searchParts.add(subtitle);
        searchParts.add(classification.category);
        for (String key : List.of(
                "name", "name:en", "official_name", "alt_name", "short_name", "brand", "operator",
                "addr:housenumber", "addr:street", "addr:place", "addr:unit", "addr:city",
                "addr:state", "addr:postcode", "cuisine", "amenity", "shop", "tourism", "leisure",
                "office", "craft", "healthcare", "emergency", "historic", "place", "sport"
        )) {
            String value = element.getTag(key);
            if (value != null && !value.isBlank()) searchParts.add(value.replace(';', ' '));
        }

        writeTsv(
                writer,
                osmType,
                Long.toString(element.getId()),
                displayName,
                subtitle,
                classification.category,
                String.format(Locale.ROOT, "%.7f", lat),
                String.format(Locale.ROOT, "%.7f", lon),
                String.join(" ", searchParts),
                Integer.toString(classification.rank)
        );
        counters.candidates++;
    }

    private static boolean isExplicitlyOutsideAustin(ReaderElement element) {
        String city = firstNonBlank(
                element.getTag("addr:city"),
                element.getTag("contact:city"),
                element.getTag("is_in:city")
        ).toLowerCase(Locale.ROOT);
        return !city.isBlank() && !city.startsWith("austin");
    }

    private static Classification classify(ReaderElement element) {
        String amenity = value(element, "amenity");
        if (isOneOf(amenity, "hospital", "clinic", "doctors", "dentist", "pharmacy")) {
            return new Classification("Healthcare", 95);
        }
        if (!value(element, "healthcare").isBlank()) {
            return new Classification("Healthcare", 92);
        }
        if (amenity.equals("fuel")) {
            return new Classification("Fuel", 90);
        }
        if (amenity.equals("charging_station")) {
            return new Classification("EV charging", 68);
        }
        if (isOneOf(
                amenity,
                "restaurant", "fast_food", "cafe", "food_court", "biergarten", "bar", "pub"
        )) {
            return new Classification("Food and drink", 88);
        }

        String leisure = value(element, "leisure");
        String landuse = value(element, "landuse");
        if (isOneOf(leisure, "park", "garden", "nature_reserve", "playground", "dog_park") ||
                isOneOf(landuse, "recreation_ground", "village_green")) {
            return new Classification("Park", 85);
        }

        String place = value(element, "place");
        if (isOneOf(place, "city", "town", "village", "suburb", "neighbourhood", "quarter")) {
            return new Classification("Place", 82);
        }
        if (!value(element, "shop").isBlank() || !value(element, "office").isBlank() ||
                !value(element, "craft").isBlank()) {
            return new Classification("Local business", 78);
        }
        if (!value(element, "tourism").isBlank()) {
            return new Classification("Attraction", 72);
        }
        if (!amenity.isBlank()) {
            return new Classification(humanize(amenity), 70);
        }
        if (!leisure.isBlank() || !value(element, "sport").isBlank()) {
            return new Classification("Recreation", 68);
        }
        if (!value(element, "historic").isBlank() || !value(element, "natural").isBlank()) {
            return new Classification("Point of interest", 65);
        }
        if (!value(element, "emergency").isBlank()) {
            return new Classification("Emergency service", 90);
        }
        if (!value(element, "public_transport").isBlank() ||
                isOneOf(value(element, "railway"), "station", "halt", "tram_stop") ||
                isOneOf(value(element, "aeroway"), "aerodrome", "terminal")) {
            return new Classification("Transport", 75);
        }
        if (isOneOf(landuse, "cemetery", "religious") ||
                isOneOf(value(element, "building"), "retail", "commercial", "hotel", "hospital")) {
            return new Classification("Point of interest", 60);
        }
        return null;
    }

    private static String formattedAddress(ReaderElement element) {
        String number = value(element, "addr:housenumber");
        String street = firstNonBlank(element.getTag("addr:street"), element.getTag("addr:place"));
        if (number.isBlank() || street.isBlank()) return "";
        String unit = value(element, "addr:unit");
        return number + " " + street + (unit.isBlank() ? "" : " #" + unit);
    }

    private static String locality(ReaderElement element) {
        String city = firstNonBlank(element.getTag("addr:city"), "Austin");
        String state = firstNonBlank(element.getTag("addr:state"), "TX");
        String postcode = value(element, "addr:postcode");
        return city + ", " + state + (postcode.isBlank() ? "" : " " + postcode);
    }

    private static String value(ReaderElement element, String key) {
        String value = element.getTag(key);
        return value == null ? "" : value.trim();
    }

    private static boolean isOneOf(String value, String... expected) {
        for (String item : expected) if (item.equals(value)) return true;
        return false;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String humanize(String value) {
        if (value.isBlank()) return "Point of interest";
        String text = value.replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static void writeTsv(BufferedWriter writer, String... values) throws Exception {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) writer.write('\t');
            writer.write(values[index].replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim());
        }
        writer.newLine();
    }

    private static long pack(double lat, double lon) {
        int latE7 = (int) Math.round(lat * 10_000_000.0);
        int lonE7 = (int) Math.round(lon * 10_000_000.0);
        return ((long) latE7 << 32) | (lonE7 & 0xffffffffL);
    }

    private static double unpackLat(long packed) {
        return ((int) (packed >> 32)) / 10_000_000.0;
    }

    private static double unpackLon(long packed) {
        return ((int) packed) / 10_000_000.0;
    }

    private static double optionDouble(Map<String, String> options, String name, double fallback) {
        String value = options.get(name);
        return value == null ? fallback : Double.parseDouble(value);
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        return minutes > 0 ? minutes + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    private record Bounds(double minLat, double maxLat, double minLon, double maxLon) {
        private Bounds {
            if (minLat >= maxLat || minLon >= maxLon) {
                throw new IllegalArgumentException("Invalid search bounds");
            }
        }

        private boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
    }

    private record Classification(String category, int rank) {
    }

    private static final class Counters {
        private long elements;
        private long candidates;
    }

    private static final class GraphBuilderOptions {
        private GraphBuilderOptions() {
        }

        private static Map<String, String> parse(String[] args) {
            if (args.length == 0 || args.length % 2 != 0) {
                throw new IllegalArgumentException(
                        "Usage: --input <texas.osm.pbf> --output <austin.tsv> " +
                                "[--min-lat N --max-lat N --min-lon N --max-lon N]"
                );
            }
            java.util.LinkedHashMap<String, String> options = new java.util.LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                options.put(args[index], args[index + 1]);
            }
            return options;
        }

        private static Path requiredPath(Map<String, String> options, String name) {
            String value = options.get(name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
            return Path.of(value).toAbsolutePath().normalize();
        }
    }
}
