package me.jezza.jcon;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Jezza
 */
public final class Rcon {
	public static final int SERVERDATA_EXECCOMMAND = 2;
	public static final int SERVERDATA_AUTH = 3;

	private static final int DEFAULT_BUFFER_SIZE = 4096;

	private static final byte[] TERMINATOR = {0, 0};

	private static final AtomicInteger REQUEST = new AtomicInteger();

	private final int requestId;
	private final Socket socket;

	/**
	 * Private because I don't like throwing exceptions in a constructor, so please use the {@link #open(String, int, String)} method to create an Rcon instance.
	 *
	 * @param requestId - The internally unique request id.
	 * @param socket    - The socked the communication will be traveling through.
	 */
	private Rcon(int requestId, Socket socket) {
		this.requestId = requestId;
		this.socket = socket;
	}

	/**
	 * Sends a command to the Rcon server.
	 *
	 * @param payload - The payload that will be sent.
	 * @return - The response as a string.
	 * @throws IOException
	 */
	public String command(String payload) throws IOException {
		if (payload == null || payload.isEmpty())
			throw new IllegalArgumentException("Invalid payload: " + payload);
		return new String(send(SERVERDATA_EXECCOMMAND, payload.getBytes()).data);
	}

	/**
	 * If you wanted to send more custom messages that the standard doesn't provide.
	 *
	 * @param type    - The type of the request.
	 * @param payload - The payload that will be sent.
	 * @return - The response object, which contains the response data.
	 * @throws IOException
	 */
	public synchronized Response send(int type, byte[] payload) throws IOException {
		return write(type, payload).read();
	}

	/**
	 * Internal write method. Used to dump all the payload and other necessary information into the {@link OutputStream} that the socket gives us.
	 *
	 * @param type    - The type of request.
	 * @param payload - The data we're sending.
	 * @return - This object, purely for chaining.
	 * @throws IOException
	 */
	private Rcon write(int type, byte[] payload) throws IOException {
		int body = payload.length + 10;
		ByteBuffer buffer = ByteBuffer.allocate(body + 4).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(body);
		buffer.putInt(requestId);
		buffer.putInt(type);
		buffer.put(payload);
		buffer.put(TERMINATOR);

		OutputStream out = socket.getOutputStream();
		out.write(buffer.array());
		out.flush();
		return this;
	}

	/**
	 * Reads a {@link Response} from the socket's {@link java.io.InputStream}.
	 *
	 * @return - A simple POJO that contains the Response data, among other things.
	 * @throws IOException
	 */
	private Response read() throws IOException {
		byte[] _buffer = new byte[DEFAULT_BUFFER_SIZE];
		int read = socket.getInputStream().read(_buffer);
		try {
			ByteBuffer buffer = ByteBuffer.wrap(_buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN);

			int length = buffer.getInt();
			int requestId = buffer.getInt();
			int type = buffer.getInt();

			byte[] payload = new byte[length - 4 - 4 - 2];
			buffer.get(payload);

			// Check if the terminators are there...
			if (buffer.remaining() != 2)
				throw new RuntimeException("Missing terminator bytes");

			return new Response(requestId, type, payload);
		} catch (IndexOutOfBoundsException e) {
			throw new RuntimeException("Invalid packet length", e);
		} catch (BufferUnderflowException e) {
			throw new RuntimeException("Failed to read packet", e);
		}
	}

	/**
	 * Disconnects this Rcon connection.
	 *
	 * @throws IOException - Throw if the socket failed to close.
	 */
	public synchronized void disconnect() throws IOException {
		socket.close();
	}

	/**
	 * Attempts to connect to an Rcon compatible server.
	 *
	 * @param host     - The host address of the server.
	 * @param port     - The port that the Rcon server will be listening on.
	 * @param password - The password that the Rcon server expects.
	 * @return - An Rcon object, used to send commands.
	 * @throws IOException - If it fails to connect, or fails to verify, exceptions will be thrown... Everywhere...
	 */
	public static Rcon open(String host, int port, String password) throws IOException {
		if (host == null || host.isEmpty())
			throw new IllegalArgumentException("Invalid host: " + host);
		if (port < 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + host);
		Rcon rcon = new Rcon(REQUEST.get(), new Socket(host, port));
		Response response = rcon.send(SERVERDATA_AUTH, password.getBytes());
		if (response.id == -1)
			throw new IllegalArgumentException("Server rejected authentication");
		return rcon;
	}

	/**
	 * A simple POJO that will probably be scratched when this project gets moved over to Kotlin.
	 */
	public static class Response {
		public final int id;
		public final int type;
		public final byte[] data;

		private Response(int id, int type, byte[] data) {
			this.id = id;
			this.type = type;
			this.data = data;
		}
	}
}
