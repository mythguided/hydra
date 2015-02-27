/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.task.pipeline;

import javax.annotation.Nonnull;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.addthis.bundle.core.Bundle;
import com.addthis.hydra.task.map.StreamEmitter;
import com.addthis.hydra.task.map.StreamMapper;
import com.addthis.hydra.task.run.TaskRunnable;

import com.google.common.collect.Sets;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This is a <span class="hydra-summary">pipeline of one or more hydra jobs</span>.
 * It is specified with {@code type : "pipeline"}.</p>
 * <p>A pipeline job will run the first job phase to completion, then the second
 * phase, then the third phase, etc. When a pipeline job stops and is restarted
 * it begins processing from the first phase. It is recommended that phases use
 * the traditional mechanisms for processing data at most once, ie. the marks
 * directories.</p>
 * <p>Use the notation {@code ${hydra.task.jobid}} to retrieve the identifier
 * of the currently running job. This should be used by downstream phases to read
 * the files produced by an upstream phase.</p>
 * <p>By default error checking is enabled to verify that each job phase
 * does not write to an output directory of another job phase. If you want
 * to disable this error checking then set {@code validateDirs} to false.</p>
 * <p>Example:</p>
 * <pre>{
 *    type : "pipeline",
 *    phases: [
 *    ]
 * }</pre>
 *
 * @user-reference
 * @hydra-name pipeline
 */
public class PipelineTask implements StreamEmitter, TaskRunnable {

    private static final Logger log = LoggerFactory.getLogger(PipelineTask.class);

    @Nonnull private final StreamMapper[] phases;

    /**
     * Field is written to by one thread and read from
     * by one or more threads.
     */
    private int currentPhase;

    /**
     * If true then ensure that output directories are all unique.
     * Default is true.
     */
    private final boolean validateDirs;

    private final Thread manager;

    /**
     * This field can be moved into the PipelineTaskManager
     * and type modifier volatile can be dropped.
     * It is declared in this scope for use in debugging purposes.
     */
    private volatile boolean phaseIsRunning = false;

    @JsonCreator
    public PipelineTask(@JsonProperty("phases") @Nonnull StreamMapper[] phases,
                        @JsonProperty("validateDirs") boolean validateDirs) {
        this.phases = phases;
        this.validateDirs = validateDirs;
        this.manager = new Thread(new PipelineTaskManager(), "PipelineTask");
        String message = validateOutputDirectories();
        if (message != null) {
            throw new IllegalStateException(message);
        }
    }

    @Override public void start() {
        manager.start();
    }

    @Override public void close() throws Exception {
        manager.interrupt();
        manager.join();
    }

    @Override public void emit(Bundle bundle) {
        assert(phaseIsRunning);
        phases[currentPhase].emit(bundle);
    }

    /**
     * Return a message string is there are one or more problems
     * validating output directories. Otherwise return null.
     */
    private String validateOutputDirectories() {
        if (!validateDirs) {
            return null;
        }
        Set<String>[] outputDirs = new Set[phases.length];
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < phases.length; i++) {
            outputDirs[i] = new HashSet<>();
            outputDirs[i].addAll(phases[i].outputRootDirs());
            for (int j = 0; j < i; j++) {
                Sets.SetView<String> intersect = Sets.intersection(outputDirs[i], outputDirs[j]);
                if (intersect.size() > 0) {
                    String message = String.format("Phases %d and %d have overlapping output directories: \"%s\"\n",
                                                   (j + 1), (i + 1), intersect.toString());
                    builder.append(message);
                }
            }
        }
        if (builder.length() > 0) {
            return builder.toString();
        } else {
            return null;
        }
    }

    private class PipelineTaskManager implements Runnable {

        @Override public void run() {

            try {
                while (currentPhase < phases.length) {
                    CompletableFuture<Void> taskComplete = phases[currentPhase].getTaskCompleteFuture();
                    log.info("Pipeline job starting phase {}", currentPhase);
                    phases[currentPhase].start();
                    phaseIsRunning = true;
                    taskComplete.get();
                    phaseIsRunning = false;
                    phases[currentPhase].close();
                    currentPhase++;
                }
            } catch (InterruptedException|ExecutionException outer) {
                if (phaseIsRunning) {
                    try {
                        phases[currentPhase].close();
                    } catch (InterruptedException inner) {
                        log.info("Pipeline task manager interrupted while closing: ", inner);
                    }
                }
            }

        }
    }

}
