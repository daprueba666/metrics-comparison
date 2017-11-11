package de.unitrier.st.soposthistory.metricscomparison;

import de.unitrier.st.soposthistory.blocks.CodeBlockVersion;
import de.unitrier.st.soposthistory.blocks.TextBlockVersion;
import de.unitrier.st.soposthistory.gt.PostBlockConnection;
import de.unitrier.st.soposthistory.gt.PostGroundTruth;
import de.unitrier.st.soposthistory.version.PostVersionList;
import org.apache.commons.csv.*;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import static de.unitrier.st.soposthistory.util.Util.getClassLogger;

// TODO: move to metrics comparison project
public class MetricComparisonManager implements Runnable {
    public static Logger logger = null;
    public static final CSVFormat csvFormatPostIds;
    public static final CSVFormat csvFormatMetricComparisonPost;
    public static final CSVFormat csvFormatMetricComparisonVersion;

    private static final Path DEFAULT_OUTPUT_DIR = Paths.get("output");

    private String name;
    private boolean addDefaultMetricsAndThresholds;
    private boolean randomizeOrder;
    private boolean validate;
    private int numberOfRepetitions;

    private Path postIdPath;
    private Path postHistoryPath;
    private Path groundTruthPath;
    private Path outputDirPath;

    private Set<Integer> postIds;
    private Map<Integer, List<Integer>> postHistoryIds;
    private Map<Integer, PostGroundTruth> postGroundTruth; // postId -> PostGroundTruth
    private Map<Integer, PostVersionList> postVersionLists; // postId -> PostVersionList
    private List<BiFunction<String, String, Double>> similarityMetrics;

    private List<String> similarityMetricsNames;
    private List<Double> similarityThresholds;

    private List<MetricComparison> metricComparisons;

    private boolean initialized;

    // See: http://nadeausoftware.com/articles/2008/03/java_tip_how_get_cpu_and_user_time_benchmarking
    private ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    static {
        // configure logger
        try {
            logger = getClassLogger(MetricComparisonManager.class);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // configure CSV format for list of PostIds
        csvFormatPostIds = CSVFormat.DEFAULT
                .withHeader("PostId", "PostTypeId", "VersionCount")
                .withDelimiter(';')
                .withQuote('"')
                .withQuoteMode(QuoteMode.MINIMAL)
                .withEscape('\\');

        // configure CSV format for metric comparison results (per version, i.e., per PostHistoryId)
        csvFormatMetricComparisonVersion = CSVFormat.DEFAULT
                .withHeader("Sample", "Metric", "Threshold", "PostId", "PostHistoryId", "PossibleConnections", "RuntimeTextTotal", "RuntimeTextUser", "TextBlockCount", "PossibleConnectionsText", "TruePositivesText", "TrueNegativesText", "FalsePositivesText", "FalseNegativesText", "RuntimeCodeTotal", "RuntimeCodeUser", "CodeBlockCount", "PossibleConnectionsCode", "TruePositivesCode", "TrueNegativesCode", "FalsePositivesCode", "FalseNegativesCode")
                .withDelimiter(';')
                .withQuote('"')
                .withQuoteMode(QuoteMode.MINIMAL)
                .withEscape('\\')
                .withNullString("null");

        // configure CSV format for metric comparison results (per post, i.e., per PostVersionList)
        csvFormatMetricComparisonPost = CSVFormat.DEFAULT
                .withHeader("Sample", "Metric", "Threshold", "PostId", "VersionCount", "PossibleConnections", "RuntimeTextTotal", "RuntimeTextUser", "TextBlockVersionCount", "PossibleConnectionsText", "TruePositivesText", "TrueNegativesText", "FalsePositivesText", "FalseNegativesText", "RuntimeCodeTotal", "RuntimeCodeUser", "CodeBlockVersionCount", "PossibleConnectionsCode", "TruePositivesCode", "TrueNegativesCode", "FalsePositivesCode", "FalseNegativesCode")
                .withDelimiter(';')
                .withQuote('"')
                .withQuoteMode(QuoteMode.MINIMAL)
                .withEscape('\\')
                .withNullString("null");
    }

    private MetricComparisonManager(String name, Path postIdPath,
                                    Path postHistoryPath, Path groundTruthPath, Path outputDirPath,
                                    boolean validate, boolean addDefaultMetricsAndThresholds, boolean randomizeOrder,
                                    int numberOfRepetitions) {
        this.name = name;

        this.postIdPath = postIdPath;
        this.postHistoryPath = postHistoryPath;
        this.groundTruthPath = groundTruthPath;
        this.outputDirPath = outputDirPath;

        this.validate = validate;
        this.addDefaultMetricsAndThresholds = addDefaultMetricsAndThresholds;
        this.randomizeOrder = randomizeOrder;
        this.numberOfRepetitions = numberOfRepetitions;

        this.postIds = new HashSet<>();
        this.postHistoryIds = new HashMap<>();
        this.postGroundTruth = new HashMap<>();
        this.postVersionLists = new HashMap<>();
        this.similarityMetrics = new LinkedList<>();
        this.similarityMetricsNames = new LinkedList<>();
        this.similarityThresholds = new LinkedList<>();
        this.metricComparisons = new LinkedList<>();

        this.initialized = false;
    }

    public static final MetricComparisonManager DEFAULT = new MetricComparisonManager(
            "MetricComparisonManager",
            null,
            null,
            null,
            DEFAULT_OUTPUT_DIR,
            true,
            true,
            true,
            4
    );

    public MetricComparisonManager withName(String name) {
        return new MetricComparisonManager(name, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions
        );
    }

    public MetricComparisonManager withInputPaths(Path postIdPath, Path postHistoryPath, Path groundTruthPath) {
        return new MetricComparisonManager(name, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions
        );
    }

    public MetricComparisonManager withOutputDirPath(Path outputDirPath) {
        return new MetricComparisonManager(name, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions
        );
    }

    public MetricComparisonManager withValidate(boolean validate) {
        return new MetricComparisonManager(name, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions
        );
    }

    public MetricComparisonManager withAddDefaultMetricsAndThresholds(boolean addDefaultMetricsAndThresholds) {
        return new MetricComparisonManager(name, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions
        );
    }

    public MetricComparisonManager withRandomizeOrder(boolean randomizeOrder) {
        return new MetricComparisonManager(name, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions
        );
    }

    public MetricComparisonManager withNumberOfRepetitions(int numberOfRepetitions) {
        return new MetricComparisonManager(name, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions
        );
    }

    public MetricComparisonManager initialize() {
        if (addDefaultMetricsAndThresholds) {
            addDefaultSimilarityMetrics();
            addDefaultSimilarityThresholds();
        }

        // ensure that input file exists (directories are tested in read methods)
        if (!Files.exists(postIdPath) || Files.isDirectory(postIdPath)) {
            throw new IllegalArgumentException("File not found: " + postIdPath);
        }

        logger.info("Creating new MetricComparisonManager " + name + " ...");

        try (CSVParser csvParser = new CSVParser(new FileReader(postIdPath.toFile()),
                csvFormatPostIds.withFirstRecordAsHeader())) {
            logger.info("Reading PostIds from CSV file " + postIdPath.toFile().toString() + " ...");

            for (CSVRecord currentRecord : csvParser) {
                int postId = Integer.parseInt(currentRecord.get("PostId"));
                int postTypeId = Integer.parseInt(currentRecord.get("PostTypeId"));
                int versionCount = Integer.parseInt(currentRecord.get("VersionCount"));

                // add post id to set
                postIds.add(postId);

                // read post version list
                PostVersionList newPostVersionList = PostVersionList.readFromCSV(
                        postHistoryPath, postId, postTypeId, false
                );
                newPostVersionList.normalizeLinks();

                if (newPostVersionList.size() != versionCount) {
                    throw new IllegalArgumentException("Version count expected to be " + versionCount
                            + ", but was " + newPostVersionList.size()
                    );
                }

                postVersionLists.put(postId, newPostVersionList);
                postHistoryIds.put(postId, newPostVersionList.getPostHistoryIds());

                // read ground truth
                PostGroundTruth newPostGroundTruth = PostGroundTruth.readFromCSV(groundTruthPath, postId);

                if (newPostGroundTruth.getPossibleConnections() != newPostVersionList.getPossibleConnections()) {
                    throw new IllegalArgumentException("Number of possible connections in ground truth is different " +
                            "from number of possible connections in post history.");
                }

                postGroundTruth.put(postId, newPostGroundTruth);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (validate && !validate()) {
            throw new IllegalArgumentException("Post ground truth files and post version history files do not match.");
        }

        initialized = true;

        return this;
    }

    public boolean validate() {
        if (postGroundTruth.size() != postVersionLists.size())
            return false;

        // check if GT and post version list contain the same posts with the same post blocks types in the same positions
        for (int postId : postVersionLists.keySet()) {
            PostGroundTruth gt = postGroundTruth.get(postId);

            if (gt == null) {
                return false;
            } else {
                PostVersionList list = postVersionLists.get(postId);

                Set<PostBlockConnection> connectionsList = list.getConnections();
                Set<PostBlockConnection> connectionsGT = gt.getConnections();

                if (!PostBlockConnection.matches(connectionsList, connectionsGT)) {
                    return false;
                }
            }
        }

        return true;
    }

    private void prepareComparison() {
        for (int postId : postIds) {
            for (double similarityThreshold : similarityThresholds) {
                for (int i = 0; i < similarityMetrics.size(); i++) {
                    BiFunction<String, String, Double> similarityMetric = similarityMetrics.get(i);
                    String similarityMetricName = similarityMetricsNames.get(i);
                    MetricComparison metricComparison = new MetricComparison(
                            postId,
                            postVersionLists.get(postId),
                            postGroundTruth.get(postId),
                            similarityMetric,
                            similarityMetricName,
                            similarityThreshold,
                            numberOfRepetitions,
                            threadMXBean
                    );
                    metricComparisons.add(metricComparison);
                }
            }
        }
    }

    private void randomizeOrder() {
        Collections.shuffle(metricComparisons, new Random());
    }

    public void compareMetrics() {
        prepareComparison();

        for (int currentRepetition = 1; currentRepetition <= numberOfRepetitions; currentRepetition++) {
            if (randomizeOrder) {
                logger.info("Randomizing order...");
                randomizeOrder();
            }

            logger.info("Starting comparison run " + currentRepetition + "...");
            int size = metricComparisons.size();
            for (int i = 0; i < metricComparisons.size(); i++) {
                MetricComparison currentMetricComparison = metricComparisons.get(i);
                // Locale.ROOT -> force '.' as decimal separator
                String progress = String.format(Locale.ROOT, "%.2f%%", (((double)(i+1))/size*100));
                logger.info("Current post: " + currentMetricComparison.getPostId() + " ("
                        + currentMetricComparison.getPostVersionList().size() + " versions)");
                logger.info("MetricComparison " + (i+1) + " of " + size + " (" + progress + "), " +
                        "repetition " + currentRepetition + " of " + numberOfRepetitions);
                currentMetricComparison.start(currentRepetition);
            }
        }
    }

    public void writeToCSV() {
        // create output directory if it does not exist
        try {
            Files.createDirectories(outputDirPath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        File outputFilePerVersion = Paths.get(this.outputDirPath.toString(), name + "_per_version.csv").toFile();

        if (outputFilePerVersion.exists()) {
            if (!outputFilePerVersion.delete()) {
                throw new IllegalStateException("Error while deleting output file: " + outputFilePerVersion);
            }
        }

        // write metric comparison results per postHistoryId and aggregate results per post
        Map<Integer, MetricResultPost> resultsPerVersionText = new HashMap<>(); // postId -> MetricResult
        Map<Integer, RuntimePost> runtimePerVersionText = new HashMap<>(); // postId -> Runtime
        Map<Integer, MetricResultPost> resultsPerVersionCode = new HashMap<>(); // postId -> MetricResult
        Map<Integer, RuntimePost> runtimePerVersionCode = new HashMap<>(); // postId -> Runtime


        logger.info("Writing metric comparison results per version to CSV file " + outputFilePerVersion.getName() + " ...");
        try (CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(outputFilePerVersion), csvFormatMetricComparisonVersion)) {
            // header is automatically written
            for (MetricComparison metricComparison : metricComparisons) {
                int postId = metricComparison.getPostId();
                List<Integer> postHistoryIdsForPost = postHistoryIds.get(postId);
                int versionCount = metricComparison.getPostVersionList().size();

                Runtime runtimeText = metricComparison.getRuntimeText();
                Runtime runtimeCode = metricComparison.getRuntimeCode();

                // save runtime results for aggregation
                if (!runtimePerVersionText.containsKey(postId)) {
                    // text
                    runtimePerVersionText.put(postId, new RuntimePost(
                            metricComparison.getSimilarityMetricName(),
                            metricComparison.getSimilarityThreshold(),
                            runtimeText.getRuntimeTotal(),
                            runtimeText.getRuntimeUser()
                    ));
                    // code
                    runtimePerVersionCode.put(postId, new RuntimePost(
                            metricComparison.getSimilarityMetricName(),
                            metricComparison.getSimilarityThreshold(),
                            runtimeCode.getRuntimeTotal(),
                            runtimeCode.getRuntimeUser()
                    ));
                } else {
                    // text
                    Runtime runtimeInMapText = runtimePerVersionText.get(postId);
                    runtimeInMapText.setRuntimeTotal(runtimeInMapText.getRuntimeTotal() != null ? runtimeInMapText.getRuntimeTotal() + runtimeText.getRuntimeTotal() : runtimeText.getRuntimeTotal());
                    runtimeInMapText.setRuntimeUser(runtimeInMapText.getRuntimeUser() != null ? runtimeInMapText.getRuntimeUser() + runtimeText.getRuntimeUser() : runtimeText.getRuntimeUser());
                    // code
                    Runtime runtimeInMapCode = runtimePerVersionCode.get(postId);
                    runtimeInMapCode.setRuntimeTotal(runtimeInMapCode.getRuntimeTotal() != null ? runtimeInMapCode.getRuntimeTotal() + runtimeCode.getRuntimeTotal() : runtimeCode.getRuntimeTotal());
                    runtimeInMapCode.setRuntimeUser(runtimeInMapCode.getRuntimeUser() != null ? runtimeInMapCode.getRuntimeUser() + runtimeCode.getRuntimeUser() : runtimeCode.getRuntimeUser());
                }

                for (int postHistoryId : postHistoryIdsForPost) {
                    MetricResult resultText = metricComparison.getResultText(postHistoryId);
                    MetricResult resultCode = metricComparison.getResultCode(postHistoryId);

                    // "Sample", "Metric", "Threshold", "PostId", "PostHistoryId", "PossibleConnections", "RuntimeTextTotal",
                    // "RuntimeTextUser", "TextBlockCount", "PossibleConnectionsText", "TruePositivesText", "TrueNegativesText",
                    // "FalsePositivesText", "FalseNegativesText", "RuntimeCodeTotal", "RuntimeCodeUser", "CodeBlockCount",
                    // "PossibleConnectionsCode", "TruePositivesCode", "TrueNegativesCode", "FalsePositivesCode", "FalseNegativesCode"
                    csvPrinter.printRecord(
                            name,
                            metricComparison.getSimilarityMetricName(),
                            metricComparison.getSimilarityThreshold(),
                            postId,
                            postHistoryId,
                            metricComparison.getPostVersionList().getPostVersion(postHistoryId).getPossibleConnections(),
                            runtimeText.getRuntimeTotal(),
                            runtimeText.getRuntimeUser(),
                            resultText.getPostBlockVersionCount(),
                            metricComparison.getPostVersionList().getPostVersion(postHistoryId).getPossibleConnections(TextBlockVersion.getPostBlockTypeIdFilter()),
                            resultText.getTruePositives(),
                            resultText.getFalsePositives(),
                            resultText.getTrueNegatives(),
                            resultText.getFalseNegatives(),
                            runtimeCode.getRuntimeTotal(),
                            runtimeCode.getRuntimeUser(),
                            resultCode.getPostBlockVersionCount(),
                            metricComparison.getPostVersionList().getPostVersion(postHistoryId).getPossibleConnections(CodeBlockVersion.getPostBlockTypeIdFilter()),
                            resultCode.getTruePositives(),
                            resultCode.getFalsePositives(),
                            resultCode.getTrueNegatives(),
                            resultCode.getFalseNegatives()
                    );

                    // save runtime results for aggregation
                    if (!resultsPerVersionText.containsKey(postId)) {
                        // text
                        resultsPerVersionText.put(postId, new MetricResultPost(
                                metricComparison.getSimilarityMetricName(),
                                metricComparison.getSimilarityThreshold(),
                                resultText.getPostBlockVersionCount(),
                                resultText.getTruePositives(),
                                resultText.getFalsePositives(),
                                resultText.getTrueNegatives(),
                                resultText.getFalseNegatives()
                        ));
                        // code
                        resultsPerVersionCode.put(postId, new MetricResultPost(
                                metricComparison.getSimilarityMetricName(),
                                metricComparison.getSimilarityThreshold(),
                                resultCode.getPostBlockVersionCount(),
                                resultCode.getTruePositives(),
                                resultCode.getFalsePositives(),
                                resultCode.getTrueNegatives(),
                                resultCode.getFalseNegatives()
                        ));
                    } else {
                        // text
                        MetricResult resultInMapText = resultsPerVersionText.get(postId);
                        resultInMapText.setPostBlockVersionCount(resultInMapText.getPostBlockVersionCount() + resultText.getPostBlockVersionCount());

                        if (resultInMapText.getTruePositives() != null) {
                            if (resultText.getTruePositives() != null) {
                                resultInMapText.setTruePositives(resultInMapText.getTruePositives() + resultText.getTruePositives());
                            }
                        } else {
                            resultInMapText.setTruePositives(resultText.getTruePositives());
                        }

                        if (resultInMapText.getFalsePositives() != null) {
                            if (resultText.getFalsePositives() != null) {
                                resultInMapText.setFalsePositives(resultInMapText.getFalsePositives() + resultText.getFalsePositives());
                            }
                        } else {
                            resultInMapText.setFalsePositives(resultText.getFalsePositives());
                        }

                        if (resultInMapText.getTrueNegatives() != null) {
                            if (resultText.getTrueNegatives() != null) {
                                resultInMapText.setTrueNegatives(resultInMapText.getTrueNegatives() + resultText.getTrueNegatives());
                            }
                        } else {
                            resultInMapText.setTrueNegatives(resultText.getTrueNegatives());
                        }

                        if (resultInMapText.getFalseNegatives() != null) {
                            if (resultText.getFalseNegatives() != null) {
                                resultInMapText.setFalseNegatives(resultInMapText.getFalseNegatives() + resultText.getFalseNegatives());
                            }
                        } else {
                            resultInMapText.setFalseNegatives(resultText.getFalseNegatives());
                        }

                        // code
                        MetricResult resultInMapCode = resultsPerVersionCode.get(postId);
                        resultInMapCode.setPostBlockVersionCount(resultInMapCode.getPostBlockVersionCount() + resultCode.getPostBlockVersionCount());

                        if (resultInMapCode.getTruePositives() != null) {
                            if (resultCode.getTruePositives() != null) {
                                resultInMapCode.setTruePositives(resultInMapCode.getTruePositives() + resultCode.getTruePositives());
                            }
                        } else {
                            resultInMapCode.setTruePositives(resultCode.getTruePositives());
                        }

                        if (resultInMapCode.getFalsePositives() != null) {
                            if (resultCode.getFalsePositives() != null) {
                                resultInMapCode.setFalsePositives(resultInMapCode.getFalsePositives() + resultCode.getFalsePositives());
                            }
                        } else {
                            resultInMapCode.setFalsePositives(resultCode.getFalsePositives());
                        }

                        if (resultInMapCode.getTrueNegatives() != null) {
                            if (resultCode.getTrueNegatives() != null) {
                                resultInMapCode.setTrueNegatives(resultInMapCode.getTrueNegatives() + resultCode.getTrueNegatives());
                            }
                        } else {
                            resultInMapCode.setTrueNegatives(resultCode.getTrueNegatives());
                        }

                        if (resultInMapCode.getFalseNegatives() != null) {
                            if (resultCode.getFalseNegatives() != null) {
                                resultInMapCode.setFalseNegatives(resultInMapCode.getFalseNegatives() + resultCode.getFalseNegatives());
                            }
                        } else {
                            resultInMapCode.setFalseNegatives(resultCode.getFalseNegatives());
                        }

                        resultInMapCode.setTruePositives(resultInMapCode.getTruePositives() != null ? resultInMapCode.getTruePositives() + resultCode.getTruePositives() : resultCode.getTruePositives());
                        resultInMapCode.setFalsePositives(resultInMapCode.getFalsePositives() != null ? resultInMapCode.getFalsePositives() + resultCode.getFalsePositives() : resultCode.getFalsePositives());
                        resultInMapCode.setTrueNegatives(resultInMapCode.getTrueNegatives() != null ? resultInMapCode.getTrueNegatives() + resultCode.getTrueNegatives() : resultCode.getTrueNegatives());
                        resultInMapCode.setFalseNegatives(resultInMapCode.getFalseNegatives() != null ? resultInMapCode.getFalseNegatives() + resultCode.getFalseNegatives() : resultCode.getFalseNegatives());
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        File outputFilePerPost = Paths.get(this.outputDirPath.toString(), name + "_per_post.csv").toFile();

        if (outputFilePerPost.exists()) {
            if (!outputFilePerPost.delete()) {
                throw new IllegalStateException("Error while deleting output file: " + outputFilePerPost);
            }
        }

        logger.info("Writing metric comparison results per post to CSV file " + outputFilePerPost.getName() + " ...");
        try (CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(outputFilePerPost), csvFormatMetricComparisonPost)) {
            // header is automatically written
            for (int postId : resultsPerVersionText.keySet()) {
                MetricResultPost resultText = resultsPerVersionText.get(postId);
                MetricResultPost resultCode = resultsPerVersionCode.get(postId);
                RuntimePost runtimeText = runtimePerVersionText.get(postId);
                RuntimePost runtimeCode = runtimePerVersionCode.get(postId);
                PostVersionList postVersionList = postVersionLists.get(postId);

                if (resultText.getPostBlockVersionCount() != postVersionList.getTextBlockVersionCount()
                    || resultCode.getPostBlockVersionCount() != postVersionList.getCodeBlockVersionCount()) {
                    throw new IllegalStateException("Version count does not match.");
                }

                // "Sample", "Metric", "Threshold", "PostId", "VersionCount", "PossibleConnections", "RuntimeTextTotal",
                // "RuntimeTextUser", "TextBlockCount", "PossibleConnectionsText", "TruePositivesText", "TrueNegativesText",
                // "FalsePositivesText", "FalseNegativesText", "RuntimeCodeTotal", "RuntimeCodeUser", "CodeBlockCount",
                // "PossibleConnectionsCode", "TruePositivesCode", "TrueNegativesCode", "FalsePositivesCode", "FalseNegativesCode"
                csvPrinter.printRecord(
                        name,
                        resultText.getSimilarityMetricName(),
                        resultText.getSimilarityThreshold(),
                        postId,
                        postVersionList.size(),
                        postVersionList.getPossibleConnections(),
                        runtimeText.getRuntimeTotal(),
                        runtimeText.getRuntimeUser(),
                        resultText.getPostBlockVersionCount(),
                        postVersionList.getPossibleConnections(TextBlockVersion.getPostBlockTypeIdFilter()),
                        resultText.getTruePositives(),
                        resultText.getFalsePositives(),
                        resultText.getTrueNegatives(),
                        resultText.getFalseNegatives(),
                        runtimeCode.getRuntimeTotal(),
                        runtimeCode.getRuntimeUser(),
                        resultCode.getPostBlockVersionCount(),
                        postVersionList.getPossibleConnections(CodeBlockVersion.getPostBlockTypeIdFilter()),
                        resultCode.getTruePositives(),
                        resultCode.getFalsePositives(),
                        resultCode.getTrueNegatives(),
                        resultCode.getFalseNegatives()
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public Map<Integer, PostGroundTruth> getPostGroundTruth() {
        return postGroundTruth;
    }

    public Map<Integer, PostVersionList> getPostVersionLists() {
        return postVersionLists;
    }

    public void addSimilarityThreshold(double threshold) {
        similarityThresholds.add(threshold);
    }

    private void addDefaultSimilarityThresholds() {
        similarityThresholds.addAll(Arrays.asList(0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)); // TODO: add also 0.35, 0.45, 0.55, 0.65, 0.75, 0.85
    }

    public void addSimilarityMetric(String name, BiFunction<String, String, Double> metric) {
        similarityMetricsNames.add(name);
        similarityMetrics.add(metric);
    }

    public MetricComparison getMetricComparison(int postId, String similarityMetricName, double similarityThreshold) {
        for (MetricComparison metricComparison : metricComparisons) {
            if (metricComparison.getPostId() == postId
                    && metricComparison.getSimilarityThreshold() == similarityThreshold
                    && metricComparison.getSimilarityMetricName().equals(similarityMetricName)) {
                return metricComparison;
            }
        }

        return null;
    }

    @Override
    public void run() {
        if (!initialized) {
            initialize();
        }
        compareMetrics();
        writeToCSV();
    }

    private void addDefaultSimilarityMetrics() {
        // ****** Edit based *****
        similarityMetrics.add(de.unitrier.st.stringsimilarity.edit.Variants::levenshtein);
        similarityMetricsNames.add("levenshtein");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.edit.Variants::levenshteinNormalized);
        similarityMetricsNames.add("levenshteinNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.edit.Variants::damerauLevenshtein);
        similarityMetricsNames.add("damerauLevenshtein");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.edit.Variants::damerauLevenshteinNormalized);
        similarityMetricsNames.add("damerauLevenshteinNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.edit.Variants::optimalAlignment);
        similarityMetricsNames.add("optimalAlignment");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.edit.Variants::optimalAlignmentNormalized);
        similarityMetricsNames.add("optimalAlignmentNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.edit.Variants::longestCommonSubsequence);
        similarityMetricsNames.add("longestCommonSubsequence");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.edit.Variants::longestCommonSubsequenceNormalized);
        similarityMetricsNames.add("longestCommonSubsequenceNormalized");

        // ****** Fingerprint based *****
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramJaccard);
        similarityMetricsNames.add("winnowingTwoGramJaccard");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramJaccard);
        similarityMetricsNames.add("winnowingThreeGramJaccard");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramJaccard);
        similarityMetricsNames.add("winnowingFourGramJaccard");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramJaccard);
        similarityMetricsNames.add("winnowingFiveGramJaccard");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramJaccardNormalized);
        similarityMetricsNames.add("winnowingTwoGramJaccardNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramJaccardNormalized);
        similarityMetricsNames.add("winnowingThreeGramJaccardNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramJaccardNormalized);
        similarityMetricsNames.add("winnowingFourGramJaccardNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramJaccardNormalized);
        similarityMetricsNames.add("winnowingFiveGramJaccardNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramDice);
        similarityMetricsNames.add("winnowingTwoGramDice");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramDice);
        similarityMetricsNames.add("winnowingThreeGramDice");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramDice);
        similarityMetricsNames.add("winnowingFourGramDice");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramDice);
        similarityMetricsNames.add("winnowingFiveGramDice");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramDiceNormalized);
        similarityMetricsNames.add("winnowingTwoGramDiceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramDiceNormalized);
        similarityMetricsNames.add("winnowingThreeGramDiceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramDiceNormalized);
        similarityMetricsNames.add("winnowingFourGramDiceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramDiceNormalized);
        similarityMetricsNames.add("winnowingFiveGramDiceNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramOverlap);
        similarityMetricsNames.add("winnowingTwoGramOverlap");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramOverlap);
        similarityMetricsNames.add("winnowingThreeGramOverlap");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramOverlap);
        similarityMetricsNames.add("winnowingFourGramOverlap");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramOverlap);
        similarityMetricsNames.add("winnowingFiveGramOverlap");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramOverlapNormalized);
        similarityMetricsNames.add("winnowingTwoGramOverlapNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramOverlapNormalized);
        similarityMetricsNames.add("winnowingThreeGramOverlapNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramOverlapNormalized);
        similarityMetricsNames.add("winnowingFourGramOverlapNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramOverlapNormalized);
        similarityMetricsNames.add("winnowingFiveGramOverlapNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramLongestCommonSubsequence);
        similarityMetricsNames.add("winnowingTwoGramLongestCommonSubsequence");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramLongestCommonSubsequence);
        similarityMetricsNames.add("winnowingThreeGramLongestCommonSubsequence");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramLongestCommonSubsequence);
        similarityMetricsNames.add("winnowingFourGramLongestCommonSubsequence");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramLongestCommonSubsequence);
        similarityMetricsNames.add("winnowingFiveGramLongestCommonSubsequence");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramLongestCommonSubsequenceNormalized);
        similarityMetricsNames.add("winnowingTwoGramLongestCommonSubsequenceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramLongestCommonSubsequenceNormalized);
        similarityMetricsNames.add("winnowingThreeGramLongestCommonSubsequenceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramLongestCommonSubsequenceNormalized);
        similarityMetricsNames.add("winnowingFourGramLongestCommonSubsequenceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramLongestCommonSubsequenceNormalized);
        similarityMetricsNames.add("winnowingFiveGramLongestCommonSubsequenceNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramOptimalAlignment);
        similarityMetricsNames.add("winnowingTwoGramOptimalAlignment");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramOptimalAlignment);
        similarityMetricsNames.add("winnowingThreeGramOptimalAlignment");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramOptimalAlignment);
        similarityMetricsNames.add("winnowingFourGramOptimalAlignment");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramOptimalAlignment);
        similarityMetricsNames.add("winnowingFiveGramOptimalAlignment");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramOptimalAlignmentNormalized);
        similarityMetricsNames.add("winnowingTwoGramOptimalAlignmentNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramOptimalAlignmentNormalized);
        similarityMetricsNames.add("winnowingThreeGramOptimalAlignmentNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramOptimalAlignmentNormalized);
        similarityMetricsNames.add("winnowingFourGramOptimalAlignmentNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramOptimalAlignmentNormalized);
        similarityMetricsNames.add("winnowingFiveGramOptimalAlignmentNormalized");

        // ****** Profile based *****
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTokenNormalizedBool);
        similarityMetricsNames.add("cosineTokenNormalizedBool");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTokenNormalizedTermFrequency);
        similarityMetricsNames.add("cosineTokenNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTokenNormalizedNormalizedTermFrequency);
        similarityMetricsNames.add("cosineTokenNormalizedNormalizedTermFrequency");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoGramNormalizedBool);
        similarityMetricsNames.add("cosineTwoGramNormalizedBool");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeGramNormalizedBool);
        similarityMetricsNames.add("cosineThreeGramNormalizedBool");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFourGramNormalizedBool);
        similarityMetricsNames.add("cosineFourGramNormalizedBool");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFiveGramNormalizedBool);
        similarityMetricsNames.add("cosineFiveGramNormalizedBool");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoGramNormalizedTermFrequency);
        similarityMetricsNames.add("cosineTwoGramNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeGramNormalizedTermFrequency);
        similarityMetricsNames.add("cosineThreeGramNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFourGramNormalizedTermFrequency);
        similarityMetricsNames.add("cosineFourGramNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFiveGramNormalizedTermFrequency);
        similarityMetricsNames.add("cosineFiveGramNormalizedTermFrequency");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoGramNormalizedNormalizedTermFrequency);
        similarityMetricsNames.add("cosineTwoGramNormalizedNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeGramNormalizedNormalizedTermFrequency);
        similarityMetricsNames.add("cosineThreeGramNormalizedNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFourGramNormalizedNormalizedTermFrequency);
        similarityMetricsNames.add("cosineFourGramNormalizedNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFiveGramNormalizedNormalizedTermFrequency);
        similarityMetricsNames.add("cosineFiveGramNormalizedNormalizedTermFrequency");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoShingleNormalizedBool);
        similarityMetricsNames.add("cosineTwoShingleNormalizedBool");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeShingleNormalizedBool);
        similarityMetricsNames.add("cosineThreeShingleNormalizedBool");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoShingleNormalizedTermFrequency);
        similarityMetricsNames.add("cosineTwoShingleNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeShingleNormalizedTermFrequency);
        similarityMetricsNames.add("cosineThreeShingleNormalizedTermFrequency");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoShingleNormalizedNormalizedTermFrequency);
        similarityMetricsNames.add("cosineTwoShingleNormalizedNormalizedTermFrequency");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeShingleNormalizedNormalizedTermFrequency);
        similarityMetricsNames.add("cosineThreeShingleNormalizedNormalizedTermFrequency");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanTokenNormalized);
        similarityMetricsNames.add("manhattanTokenNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanTwoGramNormalized);
        similarityMetricsNames.add("manhattanTwoGramNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanThreeGramNormalized);
        similarityMetricsNames.add("manhattanThreeGramNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanFourGramNormalized);
        similarityMetricsNames.add("manhattanFourGramNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanFiveGramNormalized);
        similarityMetricsNames.add("manhattanFiveGramNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanTwoShingleNormalized);
        similarityMetricsNames.add("manhattanTwoShingleNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanThreeShingleNormalized);
        similarityMetricsNames.add("manhattanThreeShingleNormalized");

        // ****** Set based *****
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenJaccard);
        similarityMetricsNames.add("tokenJaccard");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenJaccardNormalized);
        similarityMetricsNames.add("tokenJaccardNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramJaccard);
        similarityMetricsNames.add("twoGramJaccard");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramJaccard);
        similarityMetricsNames.add("threeGramJaccard");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramJaccard);
        similarityMetricsNames.add("fourGramJaccard");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramJaccard);
        similarityMetricsNames.add("fiveGramJaccard");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramJaccardNormalized);
        similarityMetricsNames.add("twoGramJaccardNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramJaccardNormalized);
        similarityMetricsNames.add("threeGramJaccardNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramJaccardNormalized);
        similarityMetricsNames.add("fourGramJaccardNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramJaccardNormalized);
        similarityMetricsNames.add("fiveGramJaccardNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramJaccardNormalizedPadding);
        similarityMetricsNames.add("twoGramJaccardNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramJaccardNormalizedPadding);
        similarityMetricsNames.add("threeGramJaccardNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramJaccardNormalizedPadding);
        similarityMetricsNames.add("fourGramJaccardNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramJaccardNormalizedPadding);
        similarityMetricsNames.add("fiveGramJaccardNormalizedPadding");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleJaccard);
        similarityMetricsNames.add("twoShingleJaccard");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleJaccard);
        similarityMetricsNames.add("threeShingleJaccard");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleJaccardNormalized);
        similarityMetricsNames.add("twoShingleJaccardNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleJaccardNormalized);
        similarityMetricsNames.add("threeShingleJaccardNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenDice);
        similarityMetricsNames.add("tokenDice");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenDiceNormalized);
        similarityMetricsNames.add("tokenDiceNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramDice);
        similarityMetricsNames.add("twoGramDice");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramDice);
        similarityMetricsNames.add("threeGramDice");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramDice);
        similarityMetricsNames.add("fourGramDice");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramDice);
        similarityMetricsNames.add("fiveGramDice");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramDiceNormalized);
        similarityMetricsNames.add("twoGramDiceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramDiceNormalized);
        similarityMetricsNames.add("threeGramDiceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramDiceNormalized);
        similarityMetricsNames.add("fourGramDiceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramDiceNormalized);
        similarityMetricsNames.add("fiveGramDiceNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramDiceNormalizedPadding);
        similarityMetricsNames.add("twoGramDiceNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramDiceNormalizedPadding);
        similarityMetricsNames.add("threeGramDiceNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramDiceNormalizedPadding);
        similarityMetricsNames.add("fourGramDiceNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramDiceNormalizedPadding);
        similarityMetricsNames.add("fiveGramDiceNormalizedPadding");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleDice);
        similarityMetricsNames.add("twoShingleDice");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleDice);
        similarityMetricsNames.add("threeShingleDice");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleDiceNormalized);
        similarityMetricsNames.add("twoShingleDiceNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleDiceNormalized);
        similarityMetricsNames.add("threeShingleDiceNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenOverlap);
        similarityMetricsNames.add("tokenOverlap");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenOverlapNormalized);
        similarityMetricsNames.add("tokenOverlapNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramOverlap);
        similarityMetricsNames.add("twoGramOverlap");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramOverlap);
        similarityMetricsNames.add("threeGramOverlap");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramOverlap);
        similarityMetricsNames.add("fourGramOverlap");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramOverlap);
        similarityMetricsNames.add("fiveGramOverlap");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramOverlapNormalized);
        similarityMetricsNames.add("twoGramOverlapNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramOverlapNormalized);
        similarityMetricsNames.add("threeGramOverlapNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramOverlapNormalized);
        similarityMetricsNames.add("fourGramOverlapNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramOverlapNormalized);
        similarityMetricsNames.add("fiveGramOverlapNormalized");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramOverlapNormalizedPadding);
        similarityMetricsNames.add("twoGramOverlapNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramOverlapNormalizedPadding);
        similarityMetricsNames.add("threeGramOverlapNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramOverlapNormalizedPadding);
        similarityMetricsNames.add("fourGramOverlapNormalizedPadding");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramOverlapNormalizedPadding);
        similarityMetricsNames.add("fiveGramOverlapNormalizedPadding");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleOverlap);
        similarityMetricsNames.add("twoShingleOverlap");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleOverlap);
        similarityMetricsNames.add("threeShingleOverlap");

        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleOverlapNormalized);
        similarityMetricsNames.add("twoShingleOverlapNormalized");
        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleOverlapNormalized);
        similarityMetricsNames.add("threeShingleOverlapNormalized");

        // much slower than other nGram based variants -> excluded after test run
//        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramSimilarityKondrak05);
//        similarityMetricsNames.add("twoGramSimilarityKondrak05");
//        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramSimilarityKondrak05);
//        similarityMetricsNames.add("threeGramSimilarityKondrak05");
//        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramSimilarityKondrak05);
//        similarityMetricsNames.add("fourGramSimilarityKondrak05");
//        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramSimilarityKondrak05);
//        similarityMetricsNames.add("fiveGramSimilarityKondrak05");
    }
}
