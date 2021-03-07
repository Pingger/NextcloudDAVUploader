package davUploader;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Panel;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;

import javax.net.ssl.SSLSocketFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * @author Pingger
 *
 */
public class ResumableUploadOperation extends JPanel implements Runnable
{
	private static byte[]	CHUNK_TRANSMISSION_FINALIZER	= "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
	private static byte[]	CRLF							= "\r\n".getBytes(StandardCharsets.UTF_8);

	protected static void parseHTTPHeadersToMap(String string, HashMap<String, String> headers)
	{
		String[] lines = string.split("\r\n");
		for (String line : lines) {
			if (!line.trim().isEmpty()) {
				String[] parts = line.split(":", 2);
				headers.put(parts[0].toLowerCase(), parts[1]);
			}
		}
	}

	public final long				customID;
	public final String				server;
	public final File				source;
	public final String				targetPath;
	public final String				user;
	protected JPanel				detailPanel;
	protected JLabel				lblFailures;
	protected JLabel				lblFile;
	protected JLabel				lblFullpath;
	protected JLabel				lblStatus;
	protected JLabel				lblTarget;

	protected Panel					wrapperPanel;

	private int						bytesPerSecondTarget	= 1024 * 1024;

	private long					bytesThisSecond			= 0;

	private long					bytesThisSecondStart	= 0;

	private LinkedList<Exception>	exceptions				= new LinkedList<>();

	private final String			pass;

	/** The start location of the current Segment in the entire source Stream */
	private long					segmentStartLocation	= 0;

	/** size of the Source */
	private final long				size;

	/** The source Stream */
	private InputStream				sourceStream			= null;
	/** The Location in the Source Stream */
	private long					sourceStreamLocation	= 0;
	/** Lock for actions on the sourceStream */
	private Object					sourceStreamLock		= new Object();
	/** How much has been uploaded */
	private long					uploaded				= 0;
	private LinkedList<Long>		uploadSpeedHistory		= new LinkedList<>();

	private ResumableUploadOperation(File source, String targetPath, String serverBase, String user, String pass) throws MalformedURLException
	{
		super(new BorderLayout());
		// Validate if a proper URL can be formed.
		URL u = new URL(serverBase + "/remote.php/dav/files/" + user + "/" + targetPath);
		u.getHost();
		this.size = source.length();
		customID = size * 11 * 13 * 17 + source.lastModified() * 13 * 17 + targetPath.hashCode() * 17;
		this.source = source;
		this.server = serverBase;
		this.targetPath = targetPath;
		this.user = user;
		this.pass = pass;
	}

	@Override
	public void run()
	{
		// TODO Auto-generated method stub

	}

	protected void buildPanels()
	{
		removeAll();
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.BOTH;
		gbc.anchor = GridBagConstraints.BASELINE_LEADING;
		JPanel north = new JPanel(new GridBagLayout());
		north.add(lblStatus = new JLabel("CREATED"), gbc);
	}

	/**
	 * Sends a CANCELUPLOAD-Request to the Server
	 *
	 * @throws IOException
	 *             if something goes wrong
	 */
	protected void cancelUpload() throws IOException
	{
		// TODO
	}

	/**
	 * @return the Common HTTP-Request-Header Fields (Host and Authorization)
	 * @implNote There is NO trailing \r\n!
	 */
	protected String getCommonRequestHeader()
	{
		String header = "Host: " + target.getHost() + "\r\n";
		header += "Authorization: " + "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
		return header;
	}

	/**
	 * Opens the SourceStream and resets the sourceStreamLocation
	 *
	 * @throws IOException
	 *             if the source can't be opened
	 */
	protected void openSourceStream() throws IOException
	{
		synchronized (sourceStreamLock) {
			if (sourceStream != null) {
				try {
					sourceStream.close();
				}
				catch (Exception ignore) {
				}
			}
			sourceStream = new FileInputStream(source);
			sourceStreamLocation = 0;
		}
	}

	/**
	 * Parses the Servers Response of the Segment to the given SegmentUploadInfo
	 *
	 * @param in
	 *            the InputStream to parse from
	 * @param sui
	 *            the {@link SegmentUploadInfo} to parse to
	 * @throws IOException
	 *             if reading fails
	 * @throws InterruptedException
	 *             if the Thread is interrupted
	 */
	protected void parseSegmentResponse(InputStream in, SegmentUploadInfo sui) throws IOException, InterruptedException
	{
		try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			long start = System.currentTimeMillis();
			while (!br.ready() && start + 5_000 < System.currentTimeMillis()) {
				Thread.sleep(5);
			}
			if (!br.ready()) { throw new IOException("No Data received!"); }
			start = System.currentTimeMillis();
			sui.responseHeader = br.readLine();
			sui.responseCode = Integer.parseInt(sui.responseHeader.split(" ", 3)[1]);
			sui.responseMessage = sui.responseHeader.split(" ", 3)[2];
			StringBuilder sb = new StringBuilder();
			String line = null;
			while ((line == null || !line.trim().isEmpty()) && start + 5_000 < System.currentTimeMillis()) {
				if (!br.ready()) {
					Thread.sleep(50);
					continue;
				}
				line = br.readLine();
				if (line == null) {
					continue;
				}
				sb.append(line + "\r\n");
			}
			parseHTTPHeadersToMap(sb.toString(), sui.headers);
		}
	}

	/**
	 * Validates the SegmentUploadInfo Response from the Server. If everything
	 * checks out, then {@link SegmentUploadInfo#validated} will be set to
	 * <code>true</code>, otherwise a corresponding exception is thrown.
	 *
	 * @param sui
	 *            the {@link SegmentUploadInfo} to validate
	 * @throws IOException
	 *             if any response is not ok
	 */
	protected void validateSegmentResponse(SegmentUploadInfo sui) throws IOException
	{
		if (sui.responseCode < 200 || sui.responseCode >= 300) {
			throw new IOException("Bad Response Code! " + sui.responseCode + " " + sui.responseMessage);
		}
		if (!sui.headers.containsKey("X-Hash-SHA256".toLowerCase())) { throw new IOException("No SHA256 provided by Server!"); }
		String sha256server = sui.headers.get("X-Hash-SHA256".toLowerCase());
		if (sui.sha256.equalsIgnoreCase(sha256server)) {
			throw new IOException("Invalid SHA256 provided by Server! (Server: " + sha256server + " | Own: " + sui.sha256);
		}
		sui.validated = true;
	}

	/**
	 * Opens a Socket to the URLs Host, then calls the provided action.
	 *
	 * @param <T>
	 *            the Type of Exception the Action might raise
	 * @param <X>
	 *            the Return type
	 * @param action
	 *            the Action to perform
	 * @return the return value of the {@link CallableWithThrows}
	 * @throws T
	 *             the Exception raised from the Action
	 * @throws IOException
	 *             the Exception raised from the Socket
	 */
	protected <T extends Throwable, X> X withSocket(CallableWithThrows<Socket, InputStream, OutputStream, T, X> action) throws T, IOException
	{
		int port = target.getPort() == -1 ? target.getDefaultPort() : target.getPort();
		if (port == -1) { throw new IllegalStateException("URL doesn't have port associated!"); }

		long start = System.currentTimeMillis();
		if (target.getProtocol().equalsIgnoreCase("https")) {
			try (
					Socket s = SSLSocketFactory.getDefault().createSocket(target.getHost(), port);
					InputStream in = s.getInputStream();
					OutputStream out = s.getOutputStream()
			)
			{
				System.out.println("Opening Socket took: " + (System.currentTimeMillis() - start) + "ms");
				return action.call(s, in, out);
			}
		}
		try (
				Socket s = new Socket(target.getHost(), port);
				InputStream in = s.getInputStream();
				OutputStream out = s.getOutputStream()
		)
		{
			System.out.println("Opening Socket took: " + (System.currentTimeMillis() - start) + "ms");
			return action.call(s, in, out);
		}
	}

	/**
	 * @return see {@link #writeSegmentContent(OutputStream)}
	 * @throws Exception
	 *             any exceptions during writing
	 */
	protected SegmentUploadInfo writeSegment() throws Exception
	{
		return withSocket((s, in, out) -> {
			/*
			 * PUT /remote.php/dav/uploads/<user>/<uploadID>/<segmentNumber>
			 * <- 201 Created
			 */
			String segmentPath = "";
			long segmentStart = -1;
			int segmentLength = -1;
			writeSegmentHeader(out, segmentPath, segmentStart, segmentLength);
			SegmentUploadInfo sui = writeSegmentContent(out);
			parseSegmentResponse(in, sui);
			validateSegmentResponse(sui);
			return sui;
		});
	}

	/**
	 * Writes the current Segment for ~10 Seconds to the OutputStream or end of
	 * Source is reached
	 *
	 * @param os
	 *            the OutputStream to write to
	 * @return the number of payload-bytes written
	 * @throws InterruptedException
	 *             if sleep is interrupted
	 * @throws IOException
	 *             if writing fails for some reason
	 */

	protected SegmentUploadInfo writeSegmentContent(OutputStream os) throws InterruptedException, IOException
	{
		SegmentUploadInfo sui = new SegmentUploadInfo();
		sui.start = System.currentTimeMillis();
		long partStart = System.currentTimeMillis();
		sui.written = 0;
		int toWrite = 0;
		int bps = getTargetSpeed();
		// while source not ended and not more than 10 seconds since start
		while (size - sui.written - segmentStartLocation > 0 && sui.start + 10_000 > System.currentTimeMillis()) {
			// Check how much should be written
			toWrite = (int) (bps * (1000.0 / (System.currentTimeMillis() - partStart))) - sui.written;
			if (toWrite < 1024 && toWrite < bps / 4) {
				Thread.sleep(50);
				continue;
			}
			partStart = System.currentTimeMillis();

			// prevent trying to write more data than exist
			toWrite = (int) Math.min(toWrite, size - sui.written - segmentStartLocation);
			byte[] chunkHeader = (Integer.toHexString(toWrite) + "\r\n").getBytes(StandardCharsets.UTF_8);

			// write Chunk
			os.write(chunkHeader);
			byte[] buf = getSourceRange(segmentStartLocation + sui.written, toWrite);
			os.write(buf);
			sui.md.update(buf);
			os.write(CRLF);

			// flush and statistics
			bps = getTargetSpeed();
			os.flush();
			sui.written += toWrite;
			addUploaded(toWrite + CRLF.length + chunkHeader.length);
		}
		// Finalize transmission
		os.write(CHUNK_TRANSMISSION_FINALIZER);
		os.flush();
		addUploaded(CHUNK_TRANSMISSION_FINALIZER.length);
		sui.end = System.currentTimeMillis();
		sui.calculateSHA256();
		return sui;
	}

	/**
	 * Writes the Header of a Segment to the given outputStream
	 *
	 * @param os
	 *            the OutputStream
	 * @param segmentPath
	 *            the segmentPath
	 * @param segmentStart
	 *            the segmentStart
	 * @param segmentLength
	 *            the segmentLength
	 * @throws IOException
	 *             if writing fails
	 */
	protected void writeSegmentHeader(OutputStream os, String segmentPath, long segmentStart, int segmentLength) throws IOException
	{
		String header = "PUT " + segmentPath + " HTTP/1.1\r\n";
		header += getCommonRequestHeader() + "\r\n";
		header += "OC-Chunk-Offset: " + segmentStart + "\r\n";
		header += "Transfer-Encoding: chunked\r\n";
		// header += "Content-Length: " + segmentLength + "\r\n";
		header += "\r\n";
		byte[] buf = header.getBytes(StandardCharsets.UTF_8);
		os.write(buf);
		os.flush();
		addUploaded(buf.length);
	}

	private void addUploaded(int count)
	{
		if (bytesThisSecondStart + 1e3 < System.currentTimeMillis()) {
			uploadSpeedHistory.add(bytesThisSecond);
			while (uploadSpeedHistory.size() > 60) {
				uploadSpeedHistory.removeFirst();
			}
			bytesThisSecond = 0;
			bytesThisSecondStart = System.currentTimeMillis();
		}
		bytesThisSecond += count;
	}

	/**
	 * Skips to the given start Location and reads the length of bytes into a
	 * byte-array. If the sourceStreamLocation is past the given start, the old
	 * Stream is closed and a new one opened.
	 *
	 * @param start
	 *            the start
	 * @param length
	 *            the amount to read
	 * @return the populated byte-array
	 * @throws IOException
	 *             if reading fails
	 */
	private byte[] getSourceRange(long start, int length) throws IOException
	{
		synchronized (sourceStreamLock) {
			if (sourceStreamLocation > segmentStartLocation) {
				openSourceStream();
			}
			long skip = segmentStartLocation - sourceStreamLocation;
			if (skip > 0) {
				sourceStream.skip(skip);
			}
			int populated = 0;
			byte[] buffer = new byte[length];
			while (populated < length) {
				int read = sourceStream.read(buffer, populated, length - populated);
				if (read == -1) { throw new IllegalArgumentException("Segment requested goes past the End of the Source!"); }
				populated += read;
			}
			return buffer;
		}
	}

	private int getTargetSpeed()
	{
		return Math.max(1024, bytesPerSecondTarget);
	}

	private static interface CallableWithThrows<S, A, B, T extends Throwable, X>
	{
		public X call(S s, A a, B b) throws T;
	}

	private static class SegmentUploadInfo
	{
		private static final String		SHA256FILL	= "0000000000000000000000000000000000000000000000000000000000000000";
		public long						end;
		public HashMap<String, String>	headers;
		public MessageDigest			md;
		public int						responseCode;
		public String					responseHeader;
		public String					responseMessage;
		public String					sha256;
		public long						start;
		public boolean					validated;
		public int						written;

		public SegmentUploadInfo()
		{
			try {
				md = MessageDigest.getInstance("SHA256");
			}
			catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}

		public void calculateSHA256()
		{
			if (sha256 != null) { return; }
			BigInteger sha256bi = new BigInteger(1, md.digest());
			md = null;
			String hexString = sha256bi.toString(16);
			sha256 = SHA256FILL.substring(hexString.length()) + hexString;
		}
	}
}
