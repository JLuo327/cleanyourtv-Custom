package com.gathin.tvhome;

import android.view.animation.Interpolator;

/**
 * A damped spring, the way tvOS moves: a fast attack, one small overshoot of about 3%, then settle.
 *
 * <p>The launcher used {@code OvershootInterpolator(1.6)} for focus, which pushes the value to about
 * 1.6 before it comes back — a tile visibly bounced out and snapped in, and the bounce was the first
 * thing the eye caught on every D-pad press. Apple's focus instead reads as weight: it arrives
 * quickly and stops. This is the closed-form response of a spring with damping ratio 0.75 and
 * natural frequency 9, sampled at the animation's own progress:
 *
 * <pre>1 - e^(-ζωt) · (cos(ωd t) + (ζω/ωd) · sin(ωd t))</pre>
 *
 * <p>Normalised so that progress 1 lands exactly on 1, so a value never sticks slightly short.
 */
final class SpringInterpolator implements Interpolator {

    private static final double ZETA = 0.75;
    private static final double OMEGA = 9.0;
    /** Damped frequency: ω·√(1-ζ²). */
    private static final double OMEGA_D = OMEGA * Math.sqrt(1 - ZETA * ZETA);

    private final double end;

    SpringInterpolator() {
        this.end = raw(1.0);
    }

    @Override
    public float getInterpolation(float input) {
        if (input <= 0f) return 0f;
        if (input >= 1f) return 1f;
        return (float) (raw(input) / end);
    }

    private static double raw(double t) {
        double decay = Math.exp(-ZETA * OMEGA * t);
        return 1 - decay * (Math.cos(OMEGA_D * t) + (ZETA * OMEGA / OMEGA_D) * Math.sin(OMEGA_D * t));
    }
}
