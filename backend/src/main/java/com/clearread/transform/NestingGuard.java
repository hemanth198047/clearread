package com.clearread.transform;

public final class NestingGuard {

    public static final class TooDeep extends RuntimeException {
        public TooDeep(int max) {
            super("Document nesting exceeds max depth of " + max);
        }
    }

    private final int maxDepth;

    public NestingGuard(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public void check(int depth) {
        if (depth > maxDepth) {
            throw new TooDeep(maxDepth);
        }
    }
}
