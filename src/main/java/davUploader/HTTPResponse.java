package davUploader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;

import davUploader.http.ChunkedTransferDecoderFactory;

/**
 *
 * @author Pingger
 *
 */
public class HTTPResponse
{
	private static final Charset							headerCharset		= StandardCharsets.US_ASCII;
	private static HashMap<String, TransferDecoderFactory>	TRANSFER_DECODERS	= new HashMap<>();
	private static String									TRANSFER_ENDODINGS_STRING;
	static {
		TRANSFER_DECODERS.put("chunked", new ChunkedTransferDecoderFactory());
	}

	/**
	 * @return all Accepted Transfer Encodings as a map backed keySet;
	 * @see HashMap#keySet()
	 */
	public static Set<String> getAcceptedTransferEncodings()
	{
		return TRANSFER_DECODERS.keySet();
	}

	/**
	 *
	 * @return the String of all accepted Transfer-Encodings in the
	 *         HTTP-RequestHeader form (e.g. "chunked, blupp, gzip, br");
	 */
	public static String getAcceptedTransferEncodingsString()
	{
		return TRANSFER_ENDODINGS_STRING;
	}

	/**
	 * Parses a String that matches the HTTPHeader without the initial request line.
	 * This does parse the entire string supplied and skips over empty lines!
	 *
	 * @implNote Header-Keys are converted to <b>lowercase</b>!
	 * @param string
	 *            the string to parse
	 * @param map
	 *            the Map to put the headers into
	 */
	public static void parseHTTPHeadersToMap(String string, HashMap<String, String> map)
	{
		String[] lines = string.split("\r\n");
		for (String line : lines) {
			parseHTTPHeaderToMap(line, map);
		}
	}

	/**
	 * Tries to parse a single line of HTTP header to the given map.
	 *
	 * @param line
	 *            the line parse
	 * @param map
	 *            the map to add to
	 */
	public static void parseHTTPHeaderToMap(String line, HashMap<String, String> map)
	{
		if (!line.trim().isEmpty()) {
			String[] parts = line.split(":", 2);
			map.put(parts[0].toLowerCase(), parts[1]);
		}
	}

	/**
	 * Sets a {@link TransferDecoderFactory} for a specific Transfer-Encoding.
	 *
	 * @param transferEncoding
	 *            the Transfer-Encoding
	 * @param tdf
	 *            the {@link TransferDecoderFactory} to set
	 */
	public static void setTransferDecoder(String transferEncoding, TransferDecoderFactory tdf)
	{
		synchronized (TRANSFER_DECODERS) {
			TRANSFER_DECODERS.put(transferEncoding.trim().toLowerCase(), tdf);

			String acc = "";
			for (String s : TRANSFER_DECODERS.keySet()) {
				acc += ", " + s;
			}
			TRANSFER_ENDODINGS_STRING = acc.substring(2);
		}
	}

	private static String readLine(InputStream in, boolean includeCRLF) throws IOException
	{
		byte[] buf = readLineArray(in);
		return new String(buf, 0, buf.length - (includeCRLF ? 0 : 2), headerCharset);
	}

	private static byte[] readLineArray(InputStream in) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		int lastByte = -1;
		int currentByte = -1;
		loop:
		while ((currentByte = in.read()) != -1) {
			baos.write(currentByte);
			// Switch-Case for performance
			switch (currentByte)
			{
				case 10: // LF as per RFC 2616
					if (lastByte == 13) { // CR as per RFC 2616
						break loop;
					}
				default:
					lastByte = currentByte;
					break;
			}
		}
		if (currentByte == -1) { throw new IOException("Stream ended unexpectedly!"); }
		byte[] buf = baos.toByteArray();
		return buf;
	}

	/** Contains the body if one exists */
	public byte[]					body			= null;

	/** Contains the headers */
	public HashMap<String, String>	headers;

	/** Contains the HTTP-Version */
	public String					HTTPVersion;

	/** Contains the HTTP Response Code */
	public int						responseCode	= -1;

	/** Contains the first line of the HTTP Response */
	public String					responseHeader	= null;

	/** Contains the HTTP Response Message */
	public String					responseMessage	= null;

	/**
	 *
	 * @param in
	 *            the InputStream to create the HTTP Response from
	 * @throws IOException
	 *             the IOExceptions that may arise during parsing of the Stream
	 */
	public HTTPResponse(InputStream in) throws IOException
	{
		headers = new HashMap<>();
		readHeader(in);
		readBody(in);
	}

	/**
	 * Reads the entire HTTP-Header from the InputStream
	 *
	 * @param in
	 *            the InputStream
	 * @throws IOException
	 *             if reading fails
	 */
	protected void readHeader(InputStream in) throws IOException
	{
		String line = "_";
		while (!line.isEmpty()) {
			line = readLine(in, false);
			// Header First Line
			if (responseHeader == null) {
				responseHeader = line;
				String[] reponseHeaderParts = responseHeader.split(" ", 3);
				responseCode = Integer.parseInt(reponseHeaderParts[1]);
				responseMessage = reponseHeaderParts[2];
				HTTPVersion = reponseHeaderParts[0];
			}
			// Read remainder of the Header
			else {
				parseHTTPHeaderToMap(line, headers);
			}
		}
	}

	private void readBody(InputStream in) throws IOException
	{
		if (!headers.containsKey("transfer-encoding")) {
			body = readNormalBody(in);
		}
		else if (headers.get("transfer-encoding").trim().equalsIgnoreCase("chunked")) {

		}
		else {
			throw new IOException("Unsupported Transfer-Encoding: " + headers.get("transfer-encoding"));
		}
	}

	private byte[] readNormalBody(InputStream in) throws IOException
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		int read;
		byte[] buffer = new byte[1024];
		do {
			read = in.read(buffer);
			baos.write(buffer, 0, read);
		}
		while (read >= 0);
		return baos.toByteArray();
	}

	/**
	 * This interface is intended to be implemented by DecoderFactories to allow
	 * easier extensibility for additional Transfer-Encodings
	 *
	 * @author Pingger
	 *
	 */
	public static interface TransferDecoderFactory
	{
		/**
		 * Constructs a new {@link InputStream} that should supply the decoded data of
		 * the given {@link InputStream}. If Actions are to be performed after the End
		 * of the decoded data, these should be performed in that
		 * {@link InputStream#read()} call, that would return the last byte. (Example:
		 * chunked-encoding has an option to supply trailing "headers", that need to be
		 * parsed of the end of the Body)
		 *
		 * @param in
		 *            the {@link InputStream} to decode
		 * @param hr
		 *            the {@link HTTPResponse} in case it is required (e.g. for Charset
		 *            information in the header)
		 * @return the constructed {@link InputStream} of decoded Data
		 */
		public abstract InputStream createDecoderStream(InputStream in, HTTPResponse hr);
	}

}
