package io.pzstorm.storm.popman;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The single-producer/single-consumer channel between the game's main thread and the population
 * worker. Main-thread setters accumulate into {@link #input()}; {@link #publish()} hands that frame
 * over and installs a fresh one. The worker folds every queued frame into one accumulator, writes
 * into {@link #output()}, and publishes it when it has something to say.
 *
 * <p>Each side touches only its own end: no method here is safe to call from both threads.
 */
public final class PopManHandoff {

    private final ConcurrentLinkedQueue<PopManInputFrame> toWorker = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PopManResultFrame> toMain = new ConcurrentLinkedQueue<>();

    private PopManInputFrame input = new PopManInputFrame();
    private PopManResultFrame output = new PopManResultFrame();

    private final PopManInputFrame workerInput = new PopManInputFrame();
    private final PopManResultFrame mainResults = new PopManResultFrame();

    /** Main thread: the frame setters write into. */
    public PopManInputFrame input() {
        return input;
    }

    /** Worker thread: the frame the simulation writes into. */
    public PopManResultFrame output() {
        return output;
    }

    /** Main thread: everything the worker produced since the last {@link #drainResults()}. */
    public PopManResultFrame results() {
        return mainResults;
    }

    /** Worker thread: everything the main thread sent since the last {@link #drainInput()}. */
    public PopManInputFrame workerInput() {
        return workerInput;
    }

    /** Either thread: a published frame the worker has not drained yet. */
    public boolean hasPendingInput() {
        return !toWorker.isEmpty();
    }

    /**
     * Main thread: hands the pending input to the worker, if there is any. Named for the native
     * {@code n_hasDataForThread}, which despite the name publishes rather than queries — its return
     * value tells the caller whether to wake the worker.
     */
    public boolean publish() {
        if (input.isEmpty()) {
            return false;
        }
        toWorker.add(input);
        input = new PopManInputFrame();
        return true;
    }

    /** Main thread: rebuilds {@link #results()} from every frame the worker has published. */
    public void drainResults() {
        mainResults.reset();
        PopManResultFrame frame;
        while ((frame = toMain.poll()) != null) {
            frame.mergeInto(mainResults);
        }
    }

    /** Worker thread: rebuilds {@link #workerInput()} from every frame the main thread has sent. */
    public void drainInput() {
        workerInput.reset();
        PopManInputFrame frame;
        while ((frame = toWorker.poll()) != null) {
            frame.mergeInto(workerInput);
        }
    }

    /**
     * Worker thread: publishes the output frame if it carries anything, then starts a fresh one.
     */
    public boolean publishResults() {
        if (output.isEmpty()) {
            return false;
        }
        toMain.add(output);
        output = new PopManResultFrame();
        return true;
    }
}
