package com.example.offnav.tools;

import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMInputFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds a small, deterministic Texas boundary asset from the local OSM state snapshot. */
public final class TexasOutlineExtractor {
    private static final String OUTER = "outer";
    private static final String INNER = "inner";
    private static final int MAX_OUTPUT_BYTES = 250_000;

    private TexasOutlineExtractor() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        Path input = requiredPath(options, "--input");
        Path output = requiredPath(options, "--output");
        double tolerance = Double.parseDouble(options.getOrDefault("--tolerance", "0.0035"));
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("OSM PBF does not exist: " + input);
        if (!Double.isFinite(tolerance) || tolerance <= 0 || tolerance > 0.05) {
            throw new IllegalArgumentException("Simplification tolerance must be within (0, 0.05]");
        }

        Instant started = Instant.now();
        Map<Long, RelationData> relations = readRelations(input);
        RelationData texas = findTexas(relations.values());
        System.out.printf(Locale.ROOT, "Texas relation: %,d (%s)%n", texas.id, texas.tags);

        Map<Long, String> wayRoles = new LinkedHashMap<>();
        collectRelationWays(texas.id, OUTER, relations, new HashSet<>(), wayRoles);
        if (wayRoles.isEmpty()) throw new IllegalStateException("Texas relation contains no boundary ways");
        System.out.printf(Locale.ROOT, "Boundary members: %,d ways%n", wayRoles.size());

        Map<Long, long[]> ways = readWays(input, wayRoles.keySet());
        if (ways.size() != wayRoles.size()) {
            Set<Long> missing = new HashSet<>(wayRoles.keySet());
            missing.removeAll(ways.keySet());
            throw new IllegalStateException("Texas boundary is missing " + missing.size() + " referenced ways");
        }

        Set<Long> nodeIds = new HashSet<>();
        for (long[] way : ways.values()) for (long node : way) nodeIds.add(node);
        Map<Long, Coordinate> coordinates = readNodes(input, nodeIds);
        if (coordinates.size() != nodeIds.size()) {
            throw new IllegalStateException("Texas boundary is missing "
                    + (nodeIds.size() - coordinates.size()) + " referenced nodes");
        }

        List<List<Coordinate>> outerRings = coordinateRings(
                assembleRings(waysForRole(ways, wayRoles, OUTER)), coordinates, tolerance, false
        );
        List<List<Coordinate>> innerRings = coordinateRings(
                assembleRings(waysForRole(ways, wayRoles, INNER)), coordinates, tolerance, true
        );
        if (outerRings.isEmpty()) throw new IllegalStateException("Texas boundary contains no closed outer ring");
        outerRings.sort(Comparator.comparingDouble(TexasOutlineExtractor::absoluteArea).reversed());

        List<PolygonData> polygons = assignHoles(outerRings, innerRings);
        validateExtent(polygons);
        String geoJson = toGeoJson(texas.id, tolerance, polygons);
        byte[] encoded = geoJson.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_OUTPUT_BYTES) {
            throw new IllegalStateException("Simplified outline is " + encoded.length
                    + " bytes; increase --tolerance to stay below " + MAX_OUTPUT_BYTES);
        }

        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        try {
            Files.write(partial, encoded);
            try {
                Files.move(partial, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable failure) {
            Files.deleteIfExists(partial);
            throw failure;
        }

        int points = polygons.stream().mapToInt(PolygonData::pointCount).sum();
        System.out.printf(
                Locale.ROOT,
                "Ready: %s (%,d bytes, %,d points, %d polygon(s), %d hole(s), elapsed %s)%n",
                output,
                encoded.length,
                points,
                polygons.size(),
                innerRings.size(),
                formatDuration(Duration.between(started, Instant.now()))
        );
    }

    private static Map<Long, RelationData> readRelations(Path input) throws Exception {
        System.out.println("Reading OSM relations");
        Map<Long, RelationData> relations = new HashMap<>();
        try (OSMInputFile osm = open(input)) {
            ReaderElement element;
            while ((element = osm.getNext()) != null) {
                if (element.getType() != ReaderElement.Type.RELATION) continue;
                ReaderRelation relation = (ReaderRelation) element;
                List<MemberData> members = new ArrayList<>(relation.getMembers().size());
                for (ReaderRelation.Member member : relation.getMembers()) {
                    members.add(new MemberData(
                            member.getType(),
                            member.getRef(),
                            member.getRole() == null ? "" : member.getRole()
                    ));
                }
                Map<String, String> tags = new HashMap<>();
                relation.getTags().forEach((key, value) -> tags.put(key, String.valueOf(value)));
                relations.put(relation.getId(), new RelationData(relation.getId(), tags, members));
            }
        }
        System.out.printf(Locale.ROOT, "Read %,d relations%n", relations.size());
        return relations;
    }

    private static RelationData findTexas(Iterable<RelationData> relations) {
        List<RelationData> isoMatches = new ArrayList<>();
        List<RelationData> fallbackMatches = new ArrayList<>();
        for (RelationData relation : relations) {
            String boundary = relation.tags.get("boundary");
            String adminLevel = relation.tags.get("admin_level");
            if (!"administrative".equals(boundary) || !"4".equals(adminLevel)) continue;
            if ("US-TX".equals(relation.tags.get("ISO3166-2"))) isoMatches.add(relation);
            if ("Texas".equals(relation.tags.get("name"))) fallbackMatches.add(relation);
        }
        List<RelationData> matches = isoMatches.isEmpty() ? fallbackMatches : isoMatches;
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one Texas admin relation, found " + matches.size());
        }
        return matches.getFirst();
    }

    private static void collectRelationWays(
            long relationId,
            String inheritedRole,
            Map<Long, RelationData> relations,
            Set<String> visited,
            Map<Long, String> wayRoles
    ) {
        String visitKey = relationId + ":" + inheritedRole;
        if (!visited.add(visitKey)) return;
        RelationData relation = relations.get(relationId);
        if (relation == null) throw new IllegalStateException("Missing nested boundary relation " + relationId);
        for (MemberData member : relation.members) {
            String role = normalizeRole(member.role, inheritedRole);
            if (member.type == ReaderElement.Type.WAY) {
                String previous = wayRoles.putIfAbsent(member.ref, role);
                if (previous != null && !previous.equals(role)) {
                    throw new IllegalStateException("Boundary way " + member.ref + " has conflicting roles");
                }
            } else if (member.type == ReaderElement.Type.RELATION) {
                collectRelationWays(member.ref, role, relations, visited, wayRoles);
            }
        }
    }

    private static String normalizeRole(String role, String inheritedRole) {
        if (INNER.equals(role)) return INNER;
        if (OUTER.equals(role)) return OUTER;
        return INNER.equals(inheritedRole) ? INNER : OUTER;
    }

    private static Map<Long, long[]> readWays(Path input, Set<Long> selected) throws Exception {
        System.out.printf(Locale.ROOT, "Reading %,d boundary ways%n", selected.size());
        Map<Long, long[]> ways = new HashMap<>();
        try (OSMInputFile osm = open(input)) {
            ReaderElement element;
            while ((element = osm.getNext()) != null) {
                if (element.getType() != ReaderElement.Type.WAY || !selected.contains(element.getId())) continue;
                ReaderWay way = (ReaderWay) element;
                long[] nodes = new long[way.getNodes().size()];
                for (int index = 0; index < nodes.length; index++) nodes[index] = way.getNodes().get(index);
                if (nodes.length < 2) throw new IllegalStateException("Boundary way has fewer than two nodes");
                ways.put(way.getId(), nodes);
            }
        }
        return ways;
    }

    private static Map<Long, Coordinate> readNodes(Path input, Set<Long> selected) throws Exception {
        System.out.printf(Locale.ROOT, "Reading %,d boundary nodes%n", selected.size());
        Map<Long, Coordinate> nodes = new HashMap<>();
        try (OSMInputFile osm = open(input)) {
            ReaderElement element;
            while ((element = osm.getNext()) != null) {
                if (element.getType() != ReaderElement.Type.NODE || !selected.contains(element.getId())) continue;
                ReaderNode node = (ReaderNode) element;
                nodes.put(node.getId(), new Coordinate(node.getLon(), node.getLat()));
            }
        }
        return nodes;
    }

    private static Map<Long, long[]> waysForRole(
            Map<Long, long[]> ways,
            Map<Long, String> roles,
            String desiredRole
    ) {
        Map<Long, long[]> selected = new LinkedHashMap<>();
        for (Map.Entry<Long, long[]> entry : ways.entrySet()) {
            if (desiredRole.equals(roles.get(entry.getKey()))) selected.put(entry.getKey(), entry.getValue());
        }
        return selected;
    }

    private static List<List<Long>> assembleRings(Map<Long, long[]> ways) {
        List<long[]> remaining = new ArrayList<>(ways.values());
        List<List<Long>> rings = new ArrayList<>();
        while (!remaining.isEmpty()) {
            long[] seed = remaining.removeLast();
            List<Long> ring = new ArrayList<>(seed.length + 32);
            for (long node : seed) ring.add(node);
            while (!ring.getFirst().equals(ring.getLast())) {
                long end = ring.getLast();
                int match = -1;
                boolean reverse = false;
                for (int index = 0; index < remaining.size(); index++) {
                    long[] candidate = remaining.get(index);
                    if (candidate[0] == end) {
                        match = index;
                        break;
                    }
                    if (candidate[candidate.length - 1] == end) {
                        match = index;
                        reverse = true;
                        break;
                    }
                }
                if (match < 0) {
                    throw new IllegalStateException("Unclosed Texas boundary ring at OSM node " + end);
                }
                long[] next = remaining.remove(match);
                if (reverse) {
                    for (int index = next.length - 2; index >= 0; index--) ring.add(next[index]);
                } else {
                    for (int index = 1; index < next.length; index++) ring.add(next[index]);
                }
            }
            rings.add(ring);
        }
        return rings;
    }

    private static List<List<Coordinate>> coordinateRings(
            List<List<Long>> nodeRings,
            Map<Long, Coordinate> coordinates,
            double tolerance,
            boolean clockwise
    ) {
        List<List<Coordinate>> result = new ArrayList<>();
        for (List<Long> nodeRing : nodeRings) {
            List<Coordinate> ring = new ArrayList<>(nodeRing.size());
            Coordinate previous = null;
            for (long nodeId : nodeRing) {
                Coordinate coordinate = coordinates.get(nodeId);
                if (coordinate == null) throw new IllegalStateException("Missing coordinate for OSM node " + nodeId);
                if (!coordinate.equals(previous)) ring.add(coordinate);
                previous = coordinate;
            }
            ring = simplifyClosed(ring, tolerance);
            if (ring.size() < 4) throw new IllegalStateException("Simplified boundary ring is invalid");
            double area = signedArea(ring);
            if ((clockwise && area > 0) || (!clockwise && area < 0)) {
                Collections.reverse(ring);
            }
            if (!ring.getFirst().equals(ring.getLast())) ring.add(ring.getFirst());
            result.add(ring);
        }
        return result;
    }

    private static List<Coordinate> simplifyClosed(List<Coordinate> closed, double tolerance) {
        if (closed.size() < 5) return closed;
        List<Coordinate> open = new ArrayList<>(closed);
        if (open.getFirst().equals(open.getLast())) open.removeLast();
        if (open.size() < 4) return closed;

        Coordinate start = open.getFirst();
        int anchor = 1;
        double farthest = -1;
        for (int index = 1; index < open.size(); index++) {
            double distance = squaredDistance(start, open.get(index));
            if (distance > farthest) {
                farthest = distance;
                anchor = index;
            }
        }

        List<Coordinate> firstHalf = new ArrayList<>(open.subList(0, anchor + 1));
        List<Coordinate> secondHalf = new ArrayList<>(open.subList(anchor, open.size()));
        secondHalf.add(start);
        List<Coordinate> simplified = simplifyOpen(firstHalf, tolerance);
        List<Coordinate> tail = simplifyOpen(secondHalf, tolerance);
        simplified.addAll(tail.subList(1, tail.size()));
        if (!simplified.getFirst().equals(simplified.getLast())) simplified.add(simplified.getFirst());
        return simplified;
    }

    private static List<Coordinate> simplifyOpen(List<Coordinate> points, double tolerance) {
        if (points.size() <= 2) return new ArrayList<>(points);
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        simplifySection(points, 0, points.size() - 1, tolerance * tolerance, keep);
        List<Coordinate> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) if (keep[index]) result.add(points.get(index));
        return result;
    }

    private static void simplifySection(
            List<Coordinate> points,
            int first,
            int last,
            double toleranceSquared,
            boolean[] keep
    ) {
        if (last <= first + 1) return;
        double maxDistance = -1;
        int farthest = -1;
        for (int index = first + 1; index < last; index++) {
            double distance = pointSegmentDistanceSquared(points.get(index), points.get(first), points.get(last));
            if (distance > maxDistance) {
                maxDistance = distance;
                farthest = index;
            }
        }
        if (maxDistance <= toleranceSquared) return;
        keep[farthest] = true;
        simplifySection(points, first, farthest, toleranceSquared, keep);
        simplifySection(points, farthest, last, toleranceSquared, keep);
    }

    private static double pointSegmentDistanceSquared(Coordinate point, Coordinate start, Coordinate end) {
        double dx = end.lon - start.lon;
        double dy = end.lat - start.lat;
        if (dx == 0 && dy == 0) return squaredDistance(point, start);
        double t = ((point.lon - start.lon) * dx + (point.lat - start.lat) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double projectedLon = start.lon + t * dx;
        double projectedLat = start.lat + t * dy;
        double lonDelta = point.lon - projectedLon;
        double latDelta = point.lat - projectedLat;
        return lonDelta * lonDelta + latDelta * latDelta;
    }

    private static double squaredDistance(Coordinate first, Coordinate second) {
        double lon = first.lon - second.lon;
        double lat = first.lat - second.lat;
        return lon * lon + lat * lat;
    }

    private static List<PolygonData> assignHoles(
            List<List<Coordinate>> outerRings,
            List<List<Coordinate>> innerRings
    ) {
        List<PolygonData> polygons = outerRings.stream().map(PolygonData::new).toList();
        for (List<Coordinate> hole : innerRings) {
            Coordinate probe = hole.getFirst();
            PolygonData owner = polygons.stream()
                    .filter(polygon -> pointInRing(probe, polygon.outer))
                    .min(Comparator.comparingDouble(polygon -> absoluteArea(polygon.outer)))
                    .orElseThrow(() -> new IllegalStateException("Texas boundary contains an unassigned inner ring"));
            owner.holes.add(hole);
        }
        return polygons;
    }

    private static boolean pointInRing(Coordinate point, List<Coordinate> ring) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            Coordinate a = ring.get(i);
            Coordinate b = ring.get(j);
            boolean crosses = (a.lat > point.lat) != (b.lat > point.lat)
                    && point.lon < (b.lon - a.lon) * (point.lat - a.lat) / (b.lat - a.lat) + a.lon;
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private static double signedArea(List<Coordinate> ring) {
        double area = 0;
        for (int index = 1; index < ring.size(); index++) {
            Coordinate previous = ring.get(index - 1);
            Coordinate current = ring.get(index);
            area += previous.lon * current.lat - current.lon * previous.lat;
        }
        return area / 2;
    }

    private static double absoluteArea(List<Coordinate> ring) {
        return Math.abs(signedArea(ring));
    }

    private static void validateExtent(List<PolygonData> polygons) {
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (PolygonData polygon : polygons) {
            for (Coordinate coordinate : polygon.outer) {
                if (!Double.isFinite(coordinate.lon) || !Double.isFinite(coordinate.lat)
                        || coordinate.lon < -180 || coordinate.lon > 180
                        || coordinate.lat < -90 || coordinate.lat > 90) {
                    throw new IllegalStateException("Texas outline contains an invalid coordinate");
                }
                minLon = Math.min(minLon, coordinate.lon);
                maxLon = Math.max(maxLon, coordinate.lon);
                minLat = Math.min(minLat, coordinate.lat);
                maxLat = Math.max(maxLat, coordinate.lat);
            }
        }
        if (minLon > -106 || maxLon < -94.5 || minLat > 26.2 || maxLat < 36.3) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "Boundary extent does not resemble Texas: %.4f,%.4f to %.4f,%.4f",
                    minLon, minLat, maxLon, maxLat
            ));
        }
        System.out.printf(
                Locale.ROOT,
                "Outline extent: %.6f,%.6f to %.6f,%.6f%n",
                minLon, minLat, maxLon, maxLat
        );
    }

    private static String toGeoJson(long relationId, double tolerance, List<PolygonData> polygons) {
        StringBuilder json = new StringBuilder(64_000);
        json.append("{\"type\":\"Feature\",\"properties\":{")
                .append("\"name\":\"Texas\",")
                .append("\"iso3166-2\":\"US-TX\",")
                .append("\"osmRelationId\":").append(relationId).append(',')
                .append("\"simplificationTolerance\":").append(format(tolerance)).append(',')
                .append("\"attribution\":\"© OpenStreetMap contributors\"")
                .append("},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[");
        for (int polygonIndex = 0; polygonIndex < polygons.size(); polygonIndex++) {
            if (polygonIndex > 0) json.append(',');
            PolygonData polygon = polygons.get(polygonIndex);
            json.append('[');
            appendRing(json, polygon.outer);
            for (List<Coordinate> hole : polygon.holes) {
                json.append(',');
                appendRing(json, hole);
            }
            json.append(']');
        }
        return json.append("]}}\n").toString();
    }

    private static void appendRing(StringBuilder json, List<Coordinate> ring) {
        json.append('[');
        for (int index = 0; index < ring.size(); index++) {
            if (index > 0) json.append(',');
            Coordinate coordinate = ring.get(index);
            json.append('[').append(format(coordinate.lon)).append(',').append(format(coordinate.lat)).append(']');
        }
        json.append(']');
    }

    private static String format(double value) {
        String formatted = String.format(Locale.ROOT, "%.6f", value);
        int end = formatted.length();
        while (end > 0 && formatted.charAt(end - 1) == '0') end--;
        if (end > 0 && formatted.charAt(end - 1) == '.') end--;
        return formatted.substring(0, end);
    }

    private static OSMInputFile open(Path input) throws Exception {
        return new OSMInputFile(input.toFile())
                .setWorkerThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))
                .open();
    }

    private static Map<String, String> parseOptions(String[] args) {
        if (args.length == 0 || args.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Usage: --input <texas.osm.pbf> --output <texas_outline.geojson> [--tolerance N]"
            );
        }
        Map<String, String> options = new LinkedHashMap<>();
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

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        return minutes > 0 ? minutes + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    private record Coordinate(double lon, double lat) {
    }

    private record MemberData(ReaderElement.Type type, long ref, String role) {
    }

    private record RelationData(long id, Map<String, String> tags, List<MemberData> members) {
    }

    private static final class PolygonData {
        private final List<Coordinate> outer;
        private final List<List<Coordinate>> holes = new ArrayList<>();

        private PolygonData(List<Coordinate> outer) {
            this.outer = outer;
        }

        private int pointCount() {
            return outer.size() + holes.stream().mapToInt(List::size).sum();
        }
    }
}
