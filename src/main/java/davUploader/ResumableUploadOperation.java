package davUploader;

import java.awt.Component;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * @author Pingger
 *
 */
public class ResumableUploadOperation implements Runnable
{
	private static byte[]	CHUNK_TRANSMISSION_FINALIZER	= "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
	private static byte[]	CRLF							= "\r\n".getBytes(StandardCharsets.UTF_8);

	/**
	 * Parses a String that matches the HTTPHeader without the initial request line.
	 * This does parse the entire string supplied and skips over empty lines!
	 *
	 * @implNote Header-Keys are converted to <b>lowercase</b>!
	 * @param string
	 *            the string to parse
	 * @param headers
	 *            the Map to put the headers into
	 */
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

	/** The ID generated for the temporary Upload-Location */
	public final long				customID;

	/** The base URL of the Server */
	public final String				server;
	/** The Source File */
	public final File				source;
	/** The Target Path on the Server (including the filename) */
	public final String				targetPath;
	/** The Nextcloud-Username */
	public final String				user;
	protected long					lastRepaint				= 0;
	protected Component				uiElement				= null;

	/** Basically the speedlimit */
	private int						bytesPerSecondTarget	= 1024 * 1024;
	/**
	 * The amount of bytes uploaded "this second". Not perfectly accurate, but good
	 * enough.
	 */
	private long					bytesThisSecond			= 0;
	/** The time, when "this second" started */
	private long					bytesThisSecondStart	= 0;
	private long					currentSegmentNumber	= 1;
	/** Exceptions are put into this List */
	private LinkedList<Exception>	exceptions				= new LinkedList<>();

	private boolean					isWindowFocused			= false;

	/** The Password/ApplicationToken */
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
	/**
	 * the complete target URL, so we have a URL to get some info from without
	 * parsing ourselves
	 */
	private final URL				target;

	/** How much has been uploaded */
	private long					uploaded				= 0;

	/**
	 * List of the previous {@link #uploadSpeedHistorySize} values of
	 * {@link #bytesThisSecond}. Newest value is at the End.
	 */
	private LinkedList<Long>		uploadSpeedHistory		= new LinkedList<>();

	private int						uploadSpeedHistorySize	= 60;

	private ResumableUploadOperation(File source, String targetPath, String serverBase, String user, String pass) throws MalformedURLException
	{
		// Validate if a proper URL can be formed.
		target = new URL(serverBase + "/remote.php/dav/files/" + user + "/" + targetPath);
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
	 *
	 * @return a new InputStream
	 * @throws IOException
	 *             of opening the Stream fails
	 */
	protected InputStream openNewSourceStream() throws IOException
	{
		return new FileInputStream(source);
	}

	/**
	 * Opens the SourceStream and resets the sourceStreamLocation
	 *
	 * @throws IOException
	 *             if the source can't be opened
	 * @implNote instead of overwriting this, instead overwrite
	 *           {@link #openNewSourceStream()}
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
			sourceStream = openNewSourceStream();
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
		sui.httpResponse = new HTTPResponse(in);
	}

	/**
	 * Checks if and how many chunks have already been uploaded.
	 */
	protected void readCurrentStatus()
	{

	}

	/**
	 * Called upon non-important ui changes. Mainly everytime a few bytes were
	 * uploaded. This ensures, that the UI is not repainted thousands of times per
	 * second, but instead ~30 times if focused and only once per second if not.<br>
	 * <br>
	 * Some UI Changes, such as State changes, force the UI to update and ignore
	 * this restriction.
	 *
	 */
	protected void smartSelfRepaint()
	{
		if (uiElement == null) { return; }
		long now = System.currentTimeMillis();
		if (isWindowFocused && lastRepaint + 33 < now || lastRepaint + 1000 < now) {
			uiElement.repaint();
		}
	}

	protected void startUpload() throws Exception, IOException
	{
		HTTPResponse r = withSocket((s, in, out) -> {
			String request = "";
			return new HTTPResponse(in);
		});
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
		if (sui.httpResponse.responseCode < 200 || sui.httpResponse.responseCode >= 300) {
			throw new IOException("Bad Response Code! " + sui.httpResponse.responseCode + " " + sui.httpResponse.responseMessage);
		}
		if (!sui.httpResponse.headers.containsKey("X-Hash-SHA256".toLowerCase())) { throw new IOException("No SHA256 provided by Server!"); }
		String sha256server = sui.httpResponse.headers.get("X-Hash-SHA256".toLowerCase());
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
			 * PUT /remote.php/dav/uploads/<user>/<uploadID>/<8-digit segmentNumber>
			 * <- 201 Created
			 */
			String segmentPath = "remote.php/dav/uploads/" + user + "/" + customID + "/" + String.format("%8d", currentSegmentNumber);
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
			while (uploadSpeedHistory.size() > uploadSpeedHistorySize) {
				uploadSpeedHistory.removeFirst();
			}
			bytesThisSecond = 0;
			bytesThisSecondStart = System.currentTimeMillis();
		}
		bytesThisSecond += count;
		smartSelfRepaint();
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
		private static final String	SHA256FILL	= "0000000000000000000000000000000000000000000000000000000000000000";
		public long					end;
		public HTTPResponse			httpResponse;
		public MessageDigest		md;
		public String				sha256;
		public long					start;
		public boolean				validated;
		public int					written;

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
