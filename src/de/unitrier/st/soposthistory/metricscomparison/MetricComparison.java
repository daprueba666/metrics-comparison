package de.unitrier.st.soposthistory.metricscomparison;

import com.google.common.base.Stopwatch;
import de.unitrier.st.soposthistory.blocks.CodeBlockVersion;
import de.unitrier.st.soposthistory.blocks.TextBlockVersion;
import de.unitrier.st.soposthistory.gt.PostBlockConnection;
import de.unitrier.st.soposthistory.gt.PostGroundTruth;
import de.unitrier.st.soposthistory.util.Config;
import de.unitrier.st.soposthistory.version.PostVersionList;
import de.unitrier.st.stringsimilarity.util.InputTooShortException;

import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static de.unitrier.st.soposthistory.metricscomparison.MetricComparisonManager.logger;

// TODO: move to metrics comparison project
public class MetricComparison {
    final private int postId;
    final private List<Integer> postHistoryIds;
    final private PostVersionList postVersionList;
    final private PostGroundTruth postGroundTruth;
    final private BiFunction<String, String, Double> similarityMetric;
    final private String similarityMetricName;
    final private double similarityThreshold;
    private boolean inputTooShort;
    private int numberOfRepetitions;
    private int currentRepetition;
    private ThreadMXBean threadMXBean;
    private Stopwatch stopWatch;

    // the following variables are used to temporarily store runtime values
    private long runtimeCPU;
    private long runtimeUser;
    private long runtimeTotal;

    // text
    private MetricRuntime metricRuntimeText;
    // PostHistoryId -> metric results for text blocks
    private Map<Integer, MetricResult> resultsText;

    // code
    private MetricRuntime metricRuntimeCode;
    // PostHistoryId -> metric results for code blocks
    private Map<Integer, MetricResult> resultsCode;

    public MetricComparison(int postId,
                            PostVersionList postVersionList,
                            PostGroundTruth postGroundTruth,
                            BiFunction<String, String, Double> similarityMetric,
                            String similarityMetricName,
                            double similarityThreshold,
                            int numberOfRepetitions,
                            ThreadMXBean threadMXBean) {
        this.postId = postId;
        this.postVersionList = postVersionList;
        // normalize links so that post version list and ground truth are comparable
        postVersionList.normalizeLinks();
        this.postGroundTruth = postGroundTruth;
        this.postHistoryIds = postVersionList.getPostHistoryIds();

        if (!this.postGroundTruth.getPostHistoryIds().equals(this.postHistoryIds)) {
            throw new IllegalArgumentException("PostHistoryIds in postVersionList and postGroundTruth differ.");
        }

        this.similarityMetric = similarityMetric;
        this.similarityMetricName = similarityMetricName;
        this.similarityThreshold = similarityThreshold;
        this.inputTooShort = false;

        this.runtimeCPU = 0;
        this.runtimeUser = 0;
        this.runtimeTotal = 0;

        this.metricRuntimeText = new MetricRuntime();
        this.metricRuntimeCode = new MetricRuntime();
        this.resultsText = new HashMap<>();
        this.resultsCode = new HashMap<>();

        this.numberOfRepetitions = numberOfRepetitions;
        this.currentRepetition = 0;

        this.threadMXBean = threadMXBean;
        this.stopWatch = Stopwatch.createUnstarted();
    }

    private void reset() {
        this.inputTooShort = false;
        this.runtimeTotal = 0;
        this.runtimeUser = 0;
        this.stopWatch.reset();
    }

    public void start(int currentRepetition) {
        Config config = Config.METRICS_COMPARISON
                .withTextSimilarityMetric(similarityMetric)
                .withTextSimilarityThreshold(similarityThreshold)
                .withCodeSimilarityMetric(similarityMetric)
                .withCodeSimilarityThreshold(similarityThreshold);

        // the post version list is shared by all metric comparisons conducted for the corresponding post
        synchronized (postVersionList) {
            this.currentRepetition++;

            if (this.currentRepetition != currentRepetition) {
                throw new IllegalStateException("Repetition count does not match (expected: " + currentRepetition
                        + "; actual: " + this.currentRepetition);
            }

            logger.info("Current metric: " + similarityMetricName + ", current threshold: " + similarityThreshold);

            // alternate the order in which the post history is processed and evaluated
            if (currentRepetition % 2 == 0) {
                evaluate(config, TextBlockVersion.getPostBlockTypeIdFilter());
                evaluate(config, CodeBlockVersion.getPostBlockTypeIdFilter());
            } else {
                evaluate(config, CodeBlockVersion.getPostBlockTypeIdFilter());
                evaluate(config, TextBlockVersion.getPostBlockTypeIdFilter());
            }
        }
    }

    private void evaluate(Config config, Set<Integer> postBlockTypeFilter) {
        long startCPUTimeNano, endCPUTimeNano;
        long startUserTimeNano, endUserTimeNano;

        // process version history and measure runtime
        stopWatch.start();
        startCPUTimeNano = threadMXBean.getCurrentThreadCpuTime();
        startUserTimeNano = threadMXBean.getCurrentThreadUserTime();
        try {
            postVersionList.processVersionHistory(config, postBlockTypeFilter);
        } catch (InputTooShortException e) {
            inputTooShort = true;
        } finally {
            endUserTimeNano = threadMXBean.getCurrentThreadUserTime();
            endCPUTimeNano = threadMXBean.getCurrentThreadCpuTime();
            stopWatch.stop();
        }

        // validate measurement of user time
        if (startUserTimeNano < 0 || endUserTimeNano < 0) {
            throw new IllegalArgumentException("User time has not been calculated correctly.");
        }

        // save runtime values
        runtimeCPU = endCPUTimeNano - startCPUTimeNano;
        runtimeUser = endUserTimeNano - startUserTimeNano;
        runtimeTotal = stopWatch.elapsed().getNano();

        // save results
        if (postBlockTypeFilter.contains(TextBlockVersion.postBlockTypeId)) {
            setResultAndRuntime(resultsText, metricRuntimeText, postBlockTypeFilter);
        }
        if (postBlockTypeFilter.contains(CodeBlockVersion.postBlockTypeId)) {
            setResultAndRuntime(resultsCode, metricRuntimeCode, postBlockTypeFilter);
        }

        // reset flag inputTooShort, stopWatch, and runtime variables
        this.reset();
        // reset post block version history
        postVersionList.resetPostBlockVersionHistory();
    }

    private void setResultAndRuntime(Map<Integer, MetricResult> results, MetricRuntime metricRuntime,
                                     Set<Integer> postBlockTypeFilter) {
        if (currentRepetition == 1) {
            // set initial values after first run, return runtimeUser
            for (int postHistoryId : postHistoryIds) {
                MetricResult result = getResultAndSetRuntime(postHistoryId, metricRuntime, postBlockTypeFilter);
                results.put(postHistoryId, result);
            }
        } else {
            // compare result values in later runs
            for (int postHistoryId : postHistoryIds) {
                MetricResult resultInMap = results.get(postHistoryId);
                MetricResult newResult = getResultAndSetRuntime(postHistoryId, metricRuntime, postBlockTypeFilter);
                boolean truePositivesEqual = (resultInMap.getTruePositives() == null && newResult.getTruePositives() == null)
                        || (resultInMap.getTruePositives() != null && newResult.getTruePositives() != null
                        && resultInMap.getTruePositives().equals(newResult.getTruePositives()));
                boolean falsePositivesEqual = (resultInMap.getFalsePositives() == null && newResult.getFalsePositives() == null)
                        || (resultInMap.getFalsePositives() != null && newResult.getFalsePositives() != null
                        && resultInMap.getFalsePositives().equals(newResult.getFalsePositives()));
                boolean trueNegativesEqual = (resultInMap.getTrueNegatives() == null && newResult.getTrueNegatives() == null)
                        || (resultInMap.getTrueNegatives() != null && newResult.getTrueNegatives() != null
                        && resultInMap.getTrueNegatives().equals(newResult.getTrueNegatives()));
                boolean falseNegativesEqual = (resultInMap.getFalseNegatives() == null && newResult.getFalseNegatives() == null)
                        || (resultInMap.getFalseNegatives() != null && newResult.getFalseNegatives() != null
                        && resultInMap.getFalseNegatives().equals(newResult.getFalseNegatives()));
                boolean postBlockVersionCountEqual = (resultInMap.getPostBlockVersionCount() == null && newResult.getPostBlockVersionCount() == null)
                        || (resultInMap.getPostBlockVersionCount() != null && newResult.getPostBlockVersionCount() != null
                        && resultInMap.getPostBlockVersionCount().equals(newResult.getPostBlockVersionCount()));

                if (!truePositivesEqual || !falsePositivesEqual || !trueNegativesEqual || !falseNegativesEqual
                        || !postBlockVersionCountEqual) {
                    throw new IllegalStateException("Metric results changed from repetition "
                            + (currentRepetition - 1) + " to " + currentRepetition);
                }
            }
        }
    }

    private MetricResult getResultAndSetRuntime(int postHistoryId, MetricRuntime metricRuntime, Set<Integer> postBlockTypeFilter) {
        MetricResult result = new MetricResult();

        // metric runtime
        if (currentRepetition == 1) {
            metricRuntime.setRuntimeTotal(runtimeTotal);
            metricRuntime.setRuntimeCPU(runtimeCPU);
            metricRuntime.setRuntimeUser(runtimeUser);
        } else if (currentRepetition < numberOfRepetitions) {
                metricRuntime.setRuntimeTotal(metricRuntime.getRuntimeTotal() + runtimeTotal);
                metricRuntime.setRuntimeCPU(metricRuntime.getRuntimeCPU() + runtimeCPU);
                metricRuntime.setRuntimeUser(metricRuntime.getRuntimeUser() + runtimeUser);
        } else {
            metricRuntime.setRuntimeTotal(Math.round((double)(metricRuntime.getRuntimeTotal() + runtimeTotal) / numberOfRepetitions));
            metricRuntime.setRuntimeCPU(Math.round((double)(metricRuntime.getRuntimeCPU() + runtimeCPU) / numberOfRepetitions));
            metricRuntime.setRuntimeUser(Math.round((double)(metricRuntime.getRuntimeUser() + runtimeUser) / numberOfRepetitions));
        }

        // post block count
        result.setPostBlockVersionCount(0);
        if (postBlockTypeFilter.contains(TextBlockVersion.postBlockTypeId))
            result.setPostBlockVersionCount(postVersionList.getPostVersion(postHistoryId).getTextBlocks().size());
        if (postBlockTypeFilter.contains(CodeBlockVersion.postBlockTypeId))
            result.setPostBlockVersionCount(postVersionList.getPostVersion(postHistoryId).getCodeBlocks().size());

        // results
        if (!inputTooShort) {
            int possibleConnections = postGroundTruth.getPossibleConnections(postHistoryId, postBlockTypeFilter);
            Set<PostBlockConnection> postBlockConnections = postVersionList.getPostVersion(postHistoryId).getConnections(postBlockTypeFilter);
            Set<PostBlockConnection> postBlockConnectionsGT = postGroundTruth.getConnections(postHistoryId, postBlockTypeFilter);

            int truePositivesCount = PostBlockConnection.intersection(postBlockConnectionsGT, postBlockConnections).size();
            int falsePositivesCount = PostBlockConnection.difference(postBlockConnections, postBlockConnectionsGT).size();

            int trueNegativesCount = possibleConnections - (PostBlockConnection.union(postBlockConnectionsGT, postBlockConnections).size());
            int falseNegativesCount = PostBlockConnection.difference(postBlockConnectionsGT, postBlockConnections).size();

            int allConnectionsCount = truePositivesCount + falsePositivesCount + trueNegativesCount + falseNegativesCount;
            if (possibleConnections != allConnectionsCount) {
                throw new IllegalStateException("Invalid result (expected: " + possibleConnections
                        + "; actual: " + allConnectionsCount + ")");
            }

            result.setTruePositives(truePositivesCount);
            result.setFalsePositives(falsePositivesCount);
            result.setTrueNegatives(trueNegativesCount);
            result.setFalseNegatives(falseNegativesCount);

        }

        return result;
    }

    public BiFunction<String, String, Double> getSimilarityMetric() {
        return similarityMetric;
    }

    public String getSimilarityMetricName() {
        return similarityMetricName;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public int getPostId() {
        return postId;
    }

    public PostVersionList getPostVersionList() {
        return postVersionList;
    }

    public MetricRuntime getMetricRuntimeText() {
        return metricRuntimeText;
    }

    public MetricRuntime getMetricRuntimeCode() {
        return metricRuntimeCode;
    }

    public MetricResult getResultText(int postHistoryId) {
        return resultsText.get(postHistoryId);
    }

    public MetricResult getResultCode(int postHistoryId) {
        return resultsCode.get(postHistoryId);
    }

    public MetricResult getAggregatedResultText() {
        MetricResult result = new MetricResult();
        for (MetricResult currentResult : resultsText.values()) {
            result.add(currentResult);
        }
        return result;
    }

    public MetricResult getAggregatedResultCode() {
        MetricResult result = new MetricResult();
        for (MetricResult currentResult : resultsCode.values()) {
            result.add(currentResult);
        }
        return result;
    }

}
