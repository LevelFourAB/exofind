package se.l4.exofind.engine.index.state;

/**
 * The two clocks a node reads to time the claims of the leadership table.
 *
 * <p>The wall clock gives the expiry that a node writes into its entries.
 * Each node has its own wall clock, and it can be wrong or step forward or
 * back, so a node does not use it to decide when a claim lapses. The
 * monotonic clock measures time that passed on this node and does not step.
 * It decides when the claims of this node lapse, and when an entry that
 * another node stopped renewing lapses.
 */
interface LeaseClock {
	/**
	 * The clocks of the system: {@link System#currentTimeMillis()} and
	 * {@link System#nanoTime()}.
	 */
	LeaseClock SYSTEM = new LeaseClock() {
		@Override
		public long wallMillis() {
			return System.currentTimeMillis();
		}

		@Override
		public long monotonicNanos() {
			return System.nanoTime();
		}
	};

	/**
	 * The wall clock, as milliseconds since the Unix epoch.
	 */
	long wallMillis();

	/**
	 * The monotonic clock, as nanoseconds from an origin that only this
	 * process knows. Only the difference between two readings has a
	 * meaning.
	 */
	long monotonicNanos();
}
