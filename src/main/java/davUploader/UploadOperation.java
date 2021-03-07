package davUploader;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.swing.JPanel;

/**
 * @author Pingger
 *
 */
@Deprecated
public class UploadOperation extends JPanel implements Runnable
{
	public static int				globalSpeedLimit		= Integer.MAX_VALUE;
	private static boolean			debug					= false;
	private static final String[]	extensions				= new String[] {
			"", "Ki", "Mi", "Gi", "Ti", "Pi", "Ei", "Zi", "Yi"
	};

	private static final Font		FONT_BIG				= new Font(Font.SANS_SERIF, Font.BOLD, 24);

	private static final Font		FONT_SMALL				= new Font(Font.SANS_SERIF, Font.PLAIN, 12);

	private static int				globalSpeedLimitDiff	= 0;

	private static long				globalSpeedLimitStart	= 0;

	public static void setDebug()
	{
		debug = true;
	}

	private static int getEffectiveSpeedLimit(int ownLimit)
	{
		int tLimit = ownLimit <= 0 ? Integer.MAX_VALUE : ownLimit;
		//if (true) { return ownLimit; }
		if (globalSpeedLimitStart + 1e3 < System.currentTimeMillis()) {
			globalSpeedLimitStart = System.currentTimeMillis();
			globalSpeedLimitDiff = 0;
		}
		int limit = Math.min(tLimit, globalSpeedLimit - globalSpeedLimitDiff);
		if (limit <= 8192) {
			limit = 8192;
		}
		globalSpeedLimitDiff += limit;
		return limit;
	}

	private static String humanReadableBytes(long bytes)
	{
		return humanReadableBytes(bytes, humanReadableUnit(bytes));
	}

	private static String humanReadableBytes(long bytes, int u)
	{
		double b = bytes / Math.pow(1024, u);
		return String.format("%.3f", b);
	}

	private static int humanReadableUnit(long bytes)
	{
		int i = 0;
		double b = bytes;
		while (b > 10000 && i < extensions.length) {
			b /= 1024;
			i++;
		}
		return i;
	}

	private static String humanReadableUnitString(int unit)
	{
		return extensions[unit] + "B";
	}

	private static void log(String l)
	{
		if (debug) {
			System.out.println(l);
		}
	}

	public final File				source;

	public final URL				targetURL;

	public final String				user;
	private MenuItem				abort;
	private byte[]					buffer				= null;
	private boolean					compress			= false;
	private HttpsURLConnection		con					= null;
	private OutputStream			conOut				= null;
	private State					currentState		= State.CREATED;
	private Deflater				df;
	private DeflaterOutputStream	dos;

	private int						failures			= 0;

	private InputStream				is					= null;

	private long					lastPaint			= 0;

	private int						lastResponseCode	= -1;

	private String					lastResponseMessage	= "";

	private long					lastUploadedRepaint	= System.currentTimeMillis();

	private Semaphore				lock				= new Semaphore(1);

	private PopupMenu				menu;

	private UI						parent				= null;

	private final String			pass;

	private int						progressW			= 0;

	private Semaphore				repaintLock			= new Semaphore(1);

	private ByteArrayOutputStream	retData				= null;

	private InputStream				retStream			= null;

	private long					size				= 0;

	private int						speedLimitPerSecond	= 0;

	private long					start				= 0;

	private long					thisSecondStart		= 0;

	private long					uploaded			= 0;

	private long					uploadedLastSecond	= 0;

	private long					uploadedThisSecond	= 0;

	/**
	 * @param source
	 * @param targetURL
	 * @param user
	 * @param pass
	 */
	public UploadOperation(File source, URL targetURL, String user, String pass)
	{
		super();
		setDoubleBuffered(true);
		this.source = source;
		this.targetURL = targetURL;
		this.user = user;
		this.pass = pass;
		this.size = source.length();
		speedLimitPerSecond = 2 * 1024 * 1024;
		speedLimitPerSecond = 256;
		setPreferredSize(new Dimension(512, 64));
		setMinimumSize(new Dimension(512, 64));
		buildPopupMenu();
		/*
		 * addMouseListener(new MouseListener()
		 * {
		 *
		 * @Override
		 * public void mouseReleased(MouseEvent e)
		 * {
		 *
		 * }
		 *
		 * @Override
		 * public void mousePressed(MouseEvent e)
		 * {
		 *
		 * }
		 *
		 * @Override
		 * public void mouseExited(MouseEvent e)
		 * {
		 *
		 * }
		 *
		 * @Override
		 * public void mouseEntered(MouseEvent e)
		 * {
		 *
		 * }
		 *
		 * @Override
		 * public void mouseClicked(MouseEvent e)
		 * {
		 * if((e.getButton()&e.BUTTON2_DOWN_MASK)>0)
		 * {
		 * menu
		 * }
		 * }
		 * });
		 */
	}

	public void cancel()
	{
		setState(State.CANCELLED);
		abort.setLabel("Remove from List");
	}

	@Override
	public boolean equals(Object obj)
	{
		if (this == obj) { return true; }
		if (obj == null) { return false; }
		if (getClass() != obj.getClass()) { return false; }
		UploadOperation other = (UploadOperation) obj;
		if (pass == null) {
			if (other.pass != null) { return false; }
		}
		else if (!pass.equals(other.pass)) { return false; }
		if (source == null) {
			if (other.source != null) { return false; }
		}
		else if (!source.equals(other.source)) { return false; }
		if (targetURL == null) {
			if (other.targetURL != null) { return false; }
		}
		else if (!targetURL.equals(other.targetURL)) { return false; }
		if (user == null) {
			if (other.user != null) { return false; }
		}
		else if (!user.equals(other.user)) { return false; }
		return true;
	}

	public State getCurrentState()
	{
		return currentState;
	}

	public String getExtendedStatusString()
	{
		StringBuilder sb = new StringBuilder();
		switch (currentState)
		{
			case COMPLETED:
				return "Completed. Failed " + failures + " times. (" + lastResponseCode + " " + lastResponseMessage + ")";

			case CONNECTING:
				return "Opening Connection to Server";

			case CREATED:
				return "Job Created";

			case FAILED:
				return "Failed " + failures + " times (" + lastResponseCode + " " + lastResponseMessage + ")";

			case FAILED_AND_WAITING:
				return "Waiting, previously failed " + failures + " times (" + lastResponseCode + " " + lastResponseMessage + ")";

			case RUNNING:
				int u = humanReadableUnit(size);
				sb.append(humanReadableBytes(uploaded, u) + " of " + humanReadableBytes(size, u) + " " + humanReadableUnitString(u));
				sb.append(" @ ");
				u = humanReadableUnit(uploadedLastSecond);
				sb.append(humanReadableBytes(uploadedLastSecond, u) + " " + humanReadableUnitString(u) + "/s");
				sb.append(" | " + Math.round(uploaded * 1000.0 / size) / 10.0 + "%");
				return sb.toString();

			case WAITING:
				return "Waiting";

		}
		return sb.toString();
	}

	public String getStatus()
	{
		return currentState + " " + lastResponseCode + " " + lastResponseMessage;
	}

	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + (pass == null ? 0 : pass.hashCode());
		result = prime * result + (source == null ? 0 : source.hashCode());
		result = prime * result + (targetURL == null ? 0 : targetURL.hashCode());
		result = prime * result + (user == null ? 0 : user.hashCode());
		return result;
	}

	public void markQueued()
	{
		if (currentState == State.CREATED) {
			setState(State.WAITING);
		}
		else if (currentState == State.FAILED) {
			setState(State.FAILED_AND_WAITING);
		}
	}

	@Override
	public void paint(Graphics g)
	{
		g.setColor(Color.WHITE);
		int w = getWidth();
		int h = getHeight();
		g.clearRect(0, 0, w, h);
		g.fillRect(0, 0, w, h);
		switch (currentState)
		{
			case COMPLETED:
				g.setColor(new Color(160, 255, 160));
				break;

			case CONNECTING:
				break;

			case CREATED:
				break;

			case CANCELLED:
			case FAILED:
				g.setColor(new Color(80, 80, 255));
				break;

			case FAILED_AND_WAITING:
				g.setColor(new Color(255, 160, 160));
				break;

			case RUNNING:
			case WAITING:
				g.setColor(new Color(160, 160, 255));
				break;

			default:
				break;

		}
		progressW = (int) (1.0 * uploaded / size * w);
		g.fillRect(0, 0, progressW, h);
		g.setColor(Color.LIGHT_GRAY);
		g.fillRect(0, 0, w, 1);
		g.fillRect(0, h - 1, w, 1);
		Graphics sub = g.create(0, 0, 160, h);
		sub.setFont(FONT_BIG);
		sub.setColor(Color.BLACK);
		sub.drawString(currentState.name(), 8, 28);
		sub.finalize();

		sub = g.create(160, 0, w - 160, h);
		int offH = 2;
		sub.setFont(FONT_SMALL);
		int incH = sub.getFont().getSize() + 2;
		sub.setColor(Color.BLACK);

		sub.drawString(getExtendedStatusString(), 8, offH += incH);
		sub.drawString(source.getAbsolutePath(), 8, offH += incH);
		sub.drawString(targetURL.toExternalForm(), 8, offH += incH);

		sub.finalize();
	}

	public void removeParent()
	{
		parent = null;
		if (currentState == State.WAITING) {
			setState(State.CREATED);
		}
		else if (currentState == State.FAILED_AND_WAITING) {
			setState(State.FAILED);
		}
	}

	@Override
	public void run()
	{
		if (currentState == State.COMPLETED) { return; }
		if (lock.tryAcquire()) {
			try {
				log("Connecting");
				connect();
				log("Connected");
				setState(State.RUNNING);
				log("Running");
				while (is.available() > 0 && currentState != State.CANCELLED) {
					int read = is.read(buffer);
					log("Read: " + read);
					uploadOverTime(buffer, read, conOut, Math.max(1, (int) (1000.0 * read / getEffectiveSpeedLimit(speedLimitPerSecond))));
					log("Uploaded: " + uploaded + " of " + size);
				}
				if (currentState == State.CANCELLED) {
					con.disconnect();
				}
				lastResponseCode = con.getResponseCode();
				lastResponseMessage = con.getResponseMessage();
				if (lastResponseCode >= 200 && lastResponseCode < 300) {
					setState(currentState = State.COMPLETED);
					log("Completed " + lastResponseCode + " " + lastResponseMessage);
				}
				else {
					failures++;
					log("Failed " + lastResponseCode + " " + lastResponseMessage);
					setState(currentState = State.FAILED);
				}
				if (parent != null) {
					UI p = parent;
					p.remove(this);
					p.add(this);
				}
				repaint();
			}
			catch (Exception e) {
				System.err.println(uploaded);
				System.err.println(System.currentTimeMillis() - start);
				log("Exception!");
				log("" + is);
				log("" + con);
				log("" + conOut);
				if (debug) {
					e.printStackTrace();
				}
				failures++;
				currentState = State.FAILED;
				try {
					is.close();
				}
				catch (Exception ignore) {
				}
				try {
					conOut.close();
				}
				catch (Exception ignore) {
				}
				con.disconnect();
				conOut = null;
				is = null;
				buffer = null;
				e.printStackTrace();
			}
			finally {
				lock.release();
			}
		}
	}

	public void setParent(UI ui)
	{
		parent = ui;
	}

	public void setSpeedlimit(int limitInBPS)
	{
		speedLimitPerSecond = limitInBPS;
	}

	protected void addUploaded(long i)
	{
		uploadedThisSecond += i;
		uploaded += i;
		if (thisSecondStart + 1e3 < System.currentTimeMillis()) {
			uploadedLastSecond = uploadedThisSecond;
			uploadedThisSecond = 0;
			thisSecondStart = System.currentTimeMillis();
			lastUploadedRepaint = System.currentTimeMillis();
			repaint();
		}
		if (lastUploadedRepaint + 50 < System.currentTimeMillis() &&
				(int) (1.0 * uploaded / size * getWidth()) != progressW)
		{
			lastUploadedRepaint = System.currentTimeMillis();
			repaint();
		}
	}

	protected void buildPopupMenu()
	{
		menu = new PopupMenu();
		abort = new MenuItem("Abort");
		abort.addActionListener(l -> {
			if (currentState == State.CANCELLED || currentState == State.COMPLETED) {
				if (parent != null) {
					parent.remove(UploadOperation.this);
				}
			}
			else {
				cancel();
			}
		});
		add(menu);
	}

	protected void connect() throws IOException
	{
		if (conOut == null) {
			setState(State.CONNECTING);
			log("Opening Connection");
			con = (HttpsURLConnection) targetURL.openConnection();
			log("Configuring connection");
			con.setRequestMethod("PUT");
			con.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes()));
			//con.setRequestProperty("Length", "" + size);
			if (compress) {
				con.setRequestProperty("Content-Encoding", "deflate");
				con.setChunkedStreamingMode(8192);
			}
			else {
				con.setFixedLengthStreamingMode(size);
				//con.setChunkedStreamingMode(8192);
			}
			con.setRequestProperty("Content-Type", "application/octet-stream");
			con.setDoOutput(true);
			con.setDoInput(true);
			con.setConnectTimeout(0);
			con.setRequestProperty("DAV", "1, 2, resumable-upload");
			con.setReadTimeout(0);
			log("Connecting Connection");
			con.connect();
			start = System.currentTimeMillis();
			conOut = con.getOutputStream();
		}
		if (is == null) {
			log("Opening File");
			uploaded = 0;
			FileInputStream fis = new FileInputStream(source);
			if (compress) {
				throw new RuntimeException("Not yet implemented");
			}
			else {
				is = fis;
			}
			log("Creating Buffer");
			buffer = new byte[8192];
		}
	}

	protected void setState(State newState)
	{
		if (newState != currentState) {
			if (currentState != State.CANCELLED) {
				currentState = newState;
				repaint();
			}
			if (currentState == State.CANCELLED || currentState == State.COMPLETED) {
				abort.setLabel("Remove from List");
			}
		}
	}

	protected void uploadOverTime(byte[] buf, int cnt, OutputStream out, int durationInMS) throws IOException, InterruptedException
	{
		if (!(durationInMS > 0)) { throw new IllegalArgumentException("duration needs to be greater 0"); }
		long start = System.currentTimeMillis() - 1;
		log("DMS: " + durationInMS);
		for (int i = 0; i < cnt;) {
			int target = (int) ((System.currentTimeMillis() - start) * 1.0 / durationInMS * cnt);
			log("Target: " + target);
			target = Math.min(target, cnt);
			log("Targetm: " + target);
			try {
				out.write(buf, i, target - i);
			}
			catch (Exception exc) {
				System.err.println(i);
				System.err.println(target - i);
				System.err.println(Arrays.toString(buf));
				throw exc;
			}
			addUploaded(target - i);
			out.flush();
			i = target;
			if (i < cnt) {
				Thread.sleep(1);
			}
		}
	}

	public enum State
	{
		CANCELLED,
		COMPLETED,
		CONNECTING,
		CREATED,
		FAILED,
		FAILED_AND_WAITING,
		RUNNING,
		WAITING;
	}
}
