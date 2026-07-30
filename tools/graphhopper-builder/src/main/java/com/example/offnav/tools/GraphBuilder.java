package com.example.offnav.tools;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.RoadAccess;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.custom.CustomWeighting;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.shapes.GHPoint;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class GraphBuilder {
    private static final String PROFILE = "car";
    private static final int GRAPH_CONFIG_VERSION = 4;
    private static final String ENCODED_VALUES =
            "car_access,road_access,road_class,road_environment,car_average_speed";
    private static final String VERSION_ENTRY = "offnav.graph.version";

    private GraphBuilder() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path input = requiredPath(options, "--input");
        Path work = requiredPath(options, "--work");
        Path output = requiredPath(options, "--output");

        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("OSM PBF does not exist: " + input);
        }
        if (Files.exists(work)) {
            throw new IllegalArgumentException("Work directory already exists; use a new run directory: " + work);
        }

        Files.createDirectories(work);
        Path graph = work.resolve("graphhopper");
        Path archive = work.resolve("region.ghz");
        Profile profile = carProfile();
        String version = graphVersion(profile);
        AtomicReference<String> stage = new AtomicReference<>("Starting import");
        Instant started = Instant.now();

        System.out.printf(Locale.ROOT, "Input: %s (%.1f MB)%n", input, Files.size(input) / 1_000_000.0);
        System.out.println("Work directory: " + work);
        System.out.println("Graph version: " + version);

        ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "graph-build-progress");
            thread.setDaemon(true);
            return thread;
        });
        progress.scheduleAtFixedRate(
                () -> logProgress(stage.get(), started, graph),
                30,
                30,
                TimeUnit.SECONDS
        );

        try {
            stage.set("Importing OpenStreetMap roads");
            FixedCarGraphHopper hopper = new FixedCarGraphHopper(stage);
            try {
                hopper.init(graphConfig(graph, input, profile));
                hopper.importOrLoad();
            } finally {
                hopper.close();
            }
        } finally {
            progress.shutdownNow();
        }

        Files.writeString(graph.resolve(VERSION_ENTRY), version + System.lineSeparator());
        verifyGraph(graph, profile);
        createArchive(graph, archive);

        Files.createDirectories(output.getParent());
        Files.move(archive, output, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf(
                Locale.ROOT,
                "Ready: %s (%.1f MB, elapsed %s)%n",
                output,
                Files.size(output) / 1_000_000.0,
                formatDuration(Duration.between(started, Instant.now()))
        );
    }

    private static GraphHopperConfig graphConfig(
            Path graph,
            Path input,
            Profile profile
    ) {
        GraphHopperConfig config = new GraphHopperConfig()
                .putObject("graph.location", graph.toString())
                .putObject("graph.dataaccess.default_type", "MMAP")
                .putObject("graph.encoded_values", ENCODED_VALUES)
                .putObject("prepare.min_network_size", 200)
                .putObject("import.osm.ignored_highways", "")
                .setProfiles(List.of(profile))
                .setCHProfiles(List.of(new CHProfile(PROFILE)));
        if (input != null) {
            config.putObject("datareader.file", input.toString());
        }
        return config;
    }

    private static Profile carProfile() {
        CustomModel model = new CustomModel()
                .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "car_average_speed"))
                .addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
                .addToPriority(Statement.ElseIf(
                        "road_access == DESTINATION",
                        Statement.Op.MULTIPLY,
                        "0.1"
                ))
                .addToPriority(Statement.If("road_class == TRACK", Statement.Op.MULTIPLY, "0.5"))
                .addToPriority(Statement.If(
                        "road_environment == FERRY",
                        Statement.Op.MULTIPLY,
                        "0.5"
                ));
        model.setDistanceInfluence(70.0);
        return new Profile(PROFILE).setCustomModel(model);
    }

    private static String graphVersion(Profile profile) {
        return GRAPH_CONFIG_VERSION + ":" + profile.getVersion() + ":" + ENCODED_VALUES;
    }

    private static void verifyGraph(Path graph, Profile profile) {
        System.out.println("Verifying memory-mapped graph and an Austin route");
        FixedCarGraphHopper hopper = new FixedCarGraphHopper(new AtomicReference<>("Verifying"));
        try {
            hopper.init(graphConfig(graph, null, profile));
            hopper.setAllowWrites(false);
            if (!hopper.load()) {
                throw new IllegalStateException("The generated graph could not be loaded");
            }
            GHRequest request = new GHRequest(List.of(
                    new GHPoint(30.2672, -97.7431),
                    new GHPoint(30.2850, -97.7350)
            )).setProfile(PROFILE);
            GHResponse response = hopper.route(request);
            if (response.hasErrors()) {
                throw new IllegalStateException("Austin route failed: " + response.getErrors());
            }
            if (response.getBest().getDistance() <= 0) {
                throw new IllegalStateException("Austin route had no distance");
            }
            System.out.printf(
                    Locale.ROOT,
                    "Verified route: %.1f km, %.1f min%n",
                    response.getBest().getDistance() / 1_000.0,
                    response.getBest().getTime() / 60_000.0
            );
        } finally {
            hopper.close();
        }
    }

    private static void createArchive(Path graph, Path archive) throws IOException {
        System.out.println("Compressing graph asset");
        try (OutputStream fileOutput = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOutput))) {
            try (var paths = Files.walk(graph)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = graph.relativize(file).toString().replace('\\', '/');
                    if (relative.equals("gh.lock")) continue;
                    zip.putNextEntry(new ZipEntry(relative));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    System.out.printf(Locale.ROOT, "  %s (%.1f MB)%n", relative, Files.size(file) / 1_000_000.0);
                }
            }
        }
    }

    private static void logProgress(String stage, Instant started, Path graph) {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long graphBytes = directorySize(graph);
        System.out.printf(
                Locale.ROOT,
                "%s (%s, heap %.1f/%.1f GB, graph %.1f GB)%n",
                stage,
                formatDuration(Duration.between(started, Instant.now())),
                usedMemory / 1_000_000_000.0,
                runtime.maxMemory() / 1_000_000_000.0,
                graphBytes / 1_000_000_000.0
        );
    }

    private static long directorySize(Path directory) {
        if (!Files.exists(directory)) return 0L;
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0L;
                }
            }).sum();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        return minutes > 0 ? minutes + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    private static Map<String, String> parseArgs(String[] args) {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "Usage: --input <texas.osm.pbf> --work <new-directory> --output <region.ghz>"
            );
        }
        return Map.of(args[0], args[1], args[2], args[3], args[4], args[5]);
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static final class FixedCarGraphHopper extends GraphHopper {
        private final AtomicReference<String> stage;

        private FixedCarGraphHopper(AtomicReference<String> stage) {
            this.stage = stage;
        }

        @Override
        protected WeightingFactory createWeightingFactory() {
            var carAccess = encodingManager.getBooleanEncodedValue("car_access");
            var carAverageSpeed = encodingManager.getDecimalEncodedValue("car_average_speed");
            var roadAccess = encodingManager.getEnumEncodedValue("road_access", RoadAccess.class);
            var roadClass = encodingManager.getEnumEncodedValue("road_class", RoadClass.class);
            var roadEnvironment = encodingManager.getEnumEncodedValue(
                    "road_environment",
                    RoadEnvironment.class
            );

            CustomWeighting.Parameters parameters = new CustomWeighting.Parameters(
                    (edge, reverse) -> {
                        boolean accessible = reverse
                                ? edge.getReverse(carAccess)
                                : edge.get(carAccess);
                        if (!accessible) return 0.0;
                        return reverse
                                ? edge.getReverse(carAverageSpeed)
                                : edge.get(carAverageSpeed);
                    },
                    carAverageSpeed::getMaxStorableDecimal,
                    (edge, reverse) -> {
                        boolean accessible = reverse
                                ? edge.getReverse(carAccess)
                                : edge.get(carAccess);
                        if (!accessible) return 0.0;
                        double priority = 1.0;
                        if (edge.get(roadAccess) == RoadAccess.DESTINATION) priority *= 0.1;
                        if (edge.get(roadClass) == RoadClass.TRACK) priority *= 0.5;
                        if (edge.get(roadEnvironment) == RoadEnvironment.FERRY) priority *= 0.5;
                        return priority;
                    },
                    () -> 1.0,
                    70.0,
                    Parameters.Routing.DEFAULT_HEADING_PENALTY
            );

            return (profile, requestHints, disableTurnCosts) -> {
                if (!PROFILE.equals(profile.getName())) {
                    throw new IllegalArgumentException("Unsupported routing profile: " + profile.getName());
                }
                return new CustomWeighting(TurnCostProvider.NO_TURN_COST_PROVIDER, parameters);
            };
        }

        @Override
        protected void importOSM() {
            stage.set("Reading OpenStreetMap roads");
            System.out.println(stage.get());
            super.importOSM();
        }

        @Override
        protected void postImportOSM() {
            stage.set("Finalizing imported roads");
            System.out.println(stage.get());
            super.postImportOSM();
        }

        @Override
        protected void cleanUp() {
            stage.set("Removing disconnected road networks");
            System.out.println(stage.get());
            super.cleanUp();
        }

        @Override
        protected void postProcessing(boolean closeEarly) {
            stage.set("Building routing indexes and shortcuts");
            System.out.println(stage.get());
            super.postProcessing(closeEarly);
        }

        @Override
        protected void flush() {
            stage.set("Saving routing graph");
            System.out.println(stage.get());
            super.flush();
        }
    }
}
