package davUploader;

/**
 * @author Pingger
 *
 */
public enum State
{
	/** State after the User cancelled the Operation */
	CANCELLED(true, false),
	/** State after the Operation completed successfully */
	COMPLETED(true, false),
	/**
	 * State after the Operation failed without self restarting. User input required
	 * to restart
	 */
	FAILED(false, true),
	/** State after the Operation failed, but a retry is queued */
	FAILED_AND_QUEUED(false, false),
	/** State if the Operation is paused. User input required to resume */
	PAUSED(false, true),
	/** State if waiting for previous jobs to start/resume the Operation */
	QUEUED(false, false),
	/** State while the Operation is currently running */
	RUNNING(false, false),
	/** State if the current State is unknown */
	UNKNOWN(false, false),
	;

	private final boolean	isFinalState;
	private final boolean	UserInputRequiredToResume;

	private State(boolean isFinal, boolean isUserInputRequiredToResume)
	{
		isFinalState = isFinal;
		this.UserInputRequiredToResume = isUserInputRequiredToResume;
	}

	/**
	 * @return <code>true</code> if the Operation can no longer operate
	 */
	public boolean isFinalState()
	{
		return isFinalState;
	}

	/**
	 * @return <code>true</code> if the Operation does not automatically
	 *         start/resume
	 */
	public boolean isUserInputRequiredToResume()
	{
		return UserInputRequiredToResume;
	}
}
