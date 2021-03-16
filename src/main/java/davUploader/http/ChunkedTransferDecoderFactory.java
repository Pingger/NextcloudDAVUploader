package davUploader.http;

import java.io.IOException;
import java.io.InputStream;

import davUploader.HTTPResponse;
import davUploader.HTTPResponse.TransferDecoderFactory;

public class ChunkedTransferDecoderFactory extends InputStream implements TransferDecoderFactory
{
	private int					chunkRead			= 0;
	private int					chunkSize			= -1;
	private boolean				endOfDataReached	= false;
	private final HTTPResponse	hr;
	private final InputStream	in;

	/**
	 * Create as Factory
	 */
	public ChunkedTransferDecoderFactory()
	{
		this.hr = null;
		this.in = null;
	}

	/** Create as Decoder Stream */
	public ChunkedTransferDecoderFactory(InputStream in, HTTPResponse hr)
	{
		if (in == null || hr == null) { throw new IllegalArgumentException("Neither InputStream nor HTTPResponse may be null!"); }
		this.in = in;
		this.hr = hr;
	}

	@Override
	public int available() throws IOException
	{
		if (in == null || hr == null) { throw new IOException("This is a Factory-only instance"); }
		// if own end detected
		if (endOfDataReached) { return -1; }
		// if underlying stream has ended
		if (in.available() < 0) { return -1; }
		// if underlying stream currently has no input available
		if (in.available() == 0) { return 0; }
		// else remaining size of current chunk
		return chunkSize - chunkRead;
	}

	@Override
	public InputStream createDecoderStream(InputStream input, HTTPResponse httpResponse)
	{
		return new ChunkedTransferDecoderFactory(input, httpResponse);
	}

	@Override
	public int read() throws IOException
	{
		if (in == null || hr == null) { throw new IOException("This is a Factory-only instance"); }
		if (endOfDataReached) { return -1; }
		checkChunkStatus();
		if (chunkSize - chunkRead > 0) {
			int r = in.read();
			chunkRead++;
			if (chunkSize - chunkRead == 0) {
				finalizeChunk();
			}
			return r;
		}
		throw new IOException("Invalid State!");
	}

	private void checkChunkStatus() throws IOException
	{
		if (chunkSize == -1) {
			readChunkHeader();
		}
	}

	private void finalizeChunk() throws IOException
	{
		int a = in.read();
		int b = in.read();
		if (a != 13 || b != 10) { throw new IOException("Invalid Data! Expected: 13 10, Got: " + a + " " + b); }
		readChunkHeader();
		if (chunkSize == 0) {
			endOfDataReached = true;
			readTrailers();
		}
	}

	private void readChunkHeader() throws IOException
	{
		try {
			chunkSize = Integer.parseInt(readLine(in, false), 16);
			chunkRead = 0;
		}
		catch (NumberFormatException nfexc) {
			throw new IOException("Invalid Data!", nfexc);
		}
	}

	private void readTrailers() throws IOException
	{
		String line;
		while (!(line = readLine(in, false)).isEmpty()) {
			HTTPResponse.parseHTTPHeaderToMap(line, hr.headers);
		}
	}
}
