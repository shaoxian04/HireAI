// RejectReason.java
package com.hireai.domain.biz.task.enums;

/**
 * Why a client rejected a reviewed result. A/B/C are disputable (open arbitration);
 * D is buyer's remorse on conformant work — a deterministic charge, no dispute.
 */
public enum RejectReason {
    A_MISMATCH,     // output does not match the declared spec
    B_FACTUAL,      // output contains factual errors
    C_INCOMPLETE,   // output is incomplete
    D_CHANGED_MIND; // client changed their mind; work was conformant → charged in full

    /** A/B/C open a Dispute; D_CHANGED_MIND does not. */
    public boolean opensDispute() {
        return this != D_CHANGED_MIND;
    }
}
