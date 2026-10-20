package io.freemytv;

/** Damped spring, like macOS animations: response (s) and damping (1 = no bounce). */
final class Spring {
    float value, velocity, target;
    private final float response, damping;
    /** Free My Projector: Reduce Motion (home settings): every spring jumps to its target in one frame. */
    static volatile boolean instant;

    Spring(float response, float damping, float initial) {
        this.response = response;
        this.damping = damping;
        value = target = initial;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** Advances `dt` seconds; returns true while still moving. */
    boolean step(float dt) {
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        if (instant) {   // one frame at the end position (still "moving" so the caller draws it)
            value = target;
            velocity = 0;
            return true;
        }
        float omega = (float) (2 * Math.PI / response);
        float stiffness = omega * omega, friction = 2 * damping * omega;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -stiffness * (value - target) - friction * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < 0.0008f && Math.abs(velocity) < 0.004f;
    }
}
