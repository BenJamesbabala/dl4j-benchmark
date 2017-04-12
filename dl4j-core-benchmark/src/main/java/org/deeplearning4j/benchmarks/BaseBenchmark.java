package org.deeplearning4j.benchmarks;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.listeners.BenchmarkListener;
import org.deeplearning4j.listeners.BenchmarkReport;
import org.deeplearning4j.models.ModelSelector;
import org.deeplearning4j.models.ModelType;
import org.deeplearning4j.models.TestableModel;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.parallelism.ParallelWrapper;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Benchmarks popular CNN models using the CIFAR-10 dataset.
 */
@Slf4j
public abstract class BaseBenchmark {
    protected int listenerFreq = 10;
    protected int iterations = 1
            ;
    protected static Map<ModelType,TestableModel> networks;
    protected boolean train = true;

    public void benchmark(int height, int width, int channels, int numLabels, int batchSize, int seed, String datasetName, DataSetIterator iter, ModelType modelType) throws Exception {
        long totalTime = System.currentTimeMillis();

        log.info("Building models for "+modelType+"....");
        networks = ModelSelector.select(modelType,height, width, channels, numLabels, seed, iterations);

        log.info("========================================");
        log.info("===== Benchmarking selected models =====");
        log.info("========================================");

        for (Map.Entry<ModelType, TestableModel> net : networks.entrySet()) {
            String dimensions = datasetName+" "+batchSize+"x"+channels+"x"+height+"x"+width;
            log.info("Selected: "+net.getKey().toString()+" "+dimensions);

            Model model = net.getValue().init();
            BenchmarkReport report = new BenchmarkReport(net.getKey().toString(), dimensions);
            report.setModel(model);

            model.setListeners(new ScoreIterationListener(listenerFreq), new BenchmarkListener(report));


            log.info("===== Benchmarking training iteration =====");
            if(model instanceof MultiLayerNetwork)
                ((MultiLayerNetwork) model).fit(iter);
            if(model instanceof ComputationGraph)
                ((ComputationGraph) model).fit(iter);


            log.info("===== Benchmarking forward/backward pass =====");
            /*
                Notes: popular benchmarks will measure the time it takes to set the input and feed forward
                and backward. This is consistent with benchmarks seen in the wild like this code:
                https://github.com/jcjohnson/cnn-benchmarks/blob/master/cnn_benchmark.lua
             */
            iter.reset(); // prevents NPE
            long totalForward = 0;
            long totalBackward = 0;
            long nIterations = 0;
            if(model instanceof MultiLayerNetwork) {
                while(iter.hasNext()) {
                    DataSet ds = iter.next();
                    INDArray input = ds.getFeatures();
                    INDArray labels = ds.getLabels();

                    // forward
                    long forwardTime = System.currentTimeMillis();
                    ((MultiLayerNetwork) model).setInput(input);
                    ((MultiLayerNetwork) model).setLabels(labels);
                    ((MultiLayerNetwork) model).feedForward();
                    forwardTime = System.currentTimeMillis() - forwardTime;
                    totalForward += forwardTime;

                    // backward
                    long backwardTime = System.currentTimeMillis();
                    Method m = MultiLayerNetwork.class.getDeclaredMethod("backprop"); // requires reflection
                    m.setAccessible(true);
                    m.invoke(model);
                    backwardTime = System.currentTimeMillis() - backwardTime;
                    totalBackward += backwardTime;

                    nIterations += 1;
                    if(nIterations % 100 == 0) log.info("Completed "+nIterations+" iterations");
                }
            }
            if(model instanceof ComputationGraph) {
                while(iter.hasNext()) {
                    DataSet ds = iter.next();
                    INDArray input = ds.getFeatures();
                    INDArray labels = ds.getLabels();

                    // forward
                    long forwardTime = System.currentTimeMillis();
                    ((ComputationGraph) model).setInput(0, input);
                    ((ComputationGraph) model).setLabels(labels);
                    ((ComputationGraph) model).feedForward();
                    forwardTime = System.currentTimeMillis() - forwardTime;
                    totalForward += forwardTime;

                    // backward
                    long backwardTime = System.currentTimeMillis();
                    Method m = ComputationGraph.class.getDeclaredMethod("calcBackpropGradients", boolean.class, INDArray[].class);
                    m.setAccessible(true);
                    m.invoke(model, false, null);
                    backwardTime = System.currentTimeMillis() - backwardTime;
                    totalBackward += backwardTime;

                    nIterations += 1;
                    if(nIterations % 100 == 0) log.info("Completed "+nIterations+" iterations");
                }
            }
            report.setAvgFeedForward((double) totalForward / (double) nIterations);
            report.setAvgBackprop((double) totalBackward / (double) nIterations);


            log.info("=============================");
            log.info("===== Benchmark Results =====");
            log.info("=============================");

            System.out.println(report.getModelSummary());
            System.out.println(report.toString());
        }
    }
}
