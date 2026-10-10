package com.hashcode.streaming.experiments;

import com.hashcode.streaming.model.ProblemInstance;
import com.hashcode.streaming.scoring.ScoreCalculator;
import com.hashcode.streaming.solution.Solution;

/** Exhaustive reference solver for tiny instances with at most 24 cache-video decisions. */
public final class ExactSmallSolver {
    private ProblemInstance problem;
    private boolean[][] selected;
    private boolean[][] bestSelected;
    private int[] usedCapacity;
    private long bestScore = -1L;
    private long evaluatedSolutions;

    public Result solve(ProblemInstance instance) {
        long decisions = (long) instance.cacheCount() * instance.videoCount();
        if (decisions > 24) {
            throw new IllegalArgumentException(
                    "Exact solver is restricted to at most 24 decisions, got " + decisions);
        }
        problem = instance;
        bestScore = -1L;
        evaluatedSolutions = 0L;
        selected = new boolean[problem.cacheCount()][problem.videoCount()];
        bestSelected = new boolean[problem.cacheCount()][problem.videoCount()];
        usedCapacity = new int[problem.cacheCount()];
        long start = System.nanoTime();
        enumerate(0);
        Solution solution = new Solution(problem.cacheCount());
        for (int cache = 0; cache < problem.cacheCount(); cache++) {
            for (int video = 0; video < problem.videoCount(); video++) {
                if (bestSelected[cache][video]) solution.addVideo(cache, video);
            }
        }
        return new Result(solution, bestScore, evaluatedSolutions,
                System.nanoTime() - start);
    }

    private void enumerate(int decision) {
        int decisionCount = problem.cacheCount() * problem.videoCount();
        if (decision == decisionCount) {
            evaluatedSolutions++;
            Solution solution = currentSolution();
            long score = new ScoreCalculator().calculate(problem, solution);
            if (score > bestScore) {
                bestScore = score;
                for (int cache = 0; cache < problem.cacheCount(); cache++) {
                    System.arraycopy(selected[cache], 0, bestSelected[cache], 0,
                            problem.videoCount());
                }
            }
            return;
        }

        enumerate(decision + 1);
        int cache = decision / problem.videoCount();
        int video = decision % problem.videoCount();
        int size = problem.videoSizes()[video];
        if (usedCapacity[cache] + size <= problem.cacheCapacity()) {
            selected[cache][video] = true;
            usedCapacity[cache] += size;
            enumerate(decision + 1);
            usedCapacity[cache] -= size;
            selected[cache][video] = false;
        }
    }

    private Solution currentSolution() {
        Solution solution = new Solution(problem.cacheCount());
        for (int cache = 0; cache < problem.cacheCount(); cache++) {
            for (int video = 0; video < problem.videoCount(); video++) {
                if (selected[cache][video]) solution.addVideo(cache, video);
            }
        }
        return solution;
    }

    public record Result(
            Solution solution, long score, long evaluatedSolutions, long elapsedNanos) { }
}
