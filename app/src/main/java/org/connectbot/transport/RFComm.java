/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.connectbot.R;
import org.connectbot.bean.HostBean;
import org.connectbot.service.TerminalBridge;
import org.connectbot.service.TerminalManager;
import org.connectbot.util.HostDatabase;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

/**
 * RFComm transport implementation.<br/>
 * Original idea from the JTA telnet package (de.mud.telnet)
 *
 * @author Kenny Root
 *
 */
public class RFComm extends AbsTransport {
	private static final String TAG = "CB.RFComm";
	private static final String PROTOCOL = "rfcomm";

	private static final int DEFAULT_PORT = 1;

	private InputStream is;
	private OutputStream os;
//	private int width;
//	private int height;

	private boolean connected = false;
	// 0x1101 is Serial Port Service Class
	private static final UUID UUID_SPP =
			UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private final BluetoothAdapter mAdapter;
	private BluetoothSocket mSocket;

	private static final Pattern hostmask;
	static {
		hostmask = Pattern.compile("^([0-9a-z.-]+)(:(\\d+))?$", Pattern.CASE_INSENSITIVE);
	}

	public RFComm() {
		mAdapter = BluetoothAdapter.getDefaultAdapter();
	}

	/**
	 * @param host The host to connect to.
	 * @param bridge Bridge to terminal view
	 * @param manager The terminal manager
	 */
	public RFComm(HostBean host, TerminalBridge bridge, TerminalManager manager) {
		super(host, bridge, manager);
		mAdapter = BluetoothAdapter.getDefaultAdapter();
	}

	public static String getProtocolName() {
		return PROTOCOL;
	}


	private BluetoothDevice findBondedDevice(String name) {
		Log.d(TAG, "findBondedDevice:" + name);
		Set<BluetoothDevice> devices = mAdapter.getBondedDevices();

		for (BluetoothDevice device : devices) {
			Log.d(TAG, "comparing " + device.getName() + " and " + device.getAddress());
			if (device.getName().equals(name) || device.getAddress().equals(name)) {
				return device;
			}
		}
		return null;
	}

	@RequiresApi(api = Build.VERSION_CODES.GINGERBREAD_MR1)
	@Override
	public void connect() {
		// This runs within a worker thread and is a blocking call.
		// On error we need to close and disconnect
		Log.d(TAG, "connect");

		assert mSocket == null;

		BluetoothDevice device = findBondedDevice(host.getHostname());
		if (device != null) {
			try {
				Log.d(TAG, "creating socket");
				mSocket = device.createInsecureRfcommSocketToServiceRecord(
						UUID_SPP);
				Log.d(TAG, "connecting socket");
				mSocket.connect();

				is = mSocket.getInputStream();
				os = mSocket.getOutputStream();

				connected = true;
				bridge.onConnected();
			} catch (IOException e) {
				Log.e(TAG, "IO Exception connecting to host", e);
				close();
				onDisconnect();
			}
		}

	}

	private void onDisconnect() {
		bridge.dispatchDisconnect(false);
	}

	@Override
	public void close() {
		Log.d(TAG, "close");
		connected = false;
		if (mSocket != null)
			try {
				mSocket.close();
				mSocket = null;
			} catch (IOException e) {
				Log.d(TAG, "Error closing socket.", e);
			}
	}

	@Override
	public void flush() throws IOException {
		os.flush();
	}

	@Override
	public int getDefaultPort() {
		return DEFAULT_PORT;
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	@Override
	public boolean isSessionOpen() {
		return connected;
	}

	@Override
	public int read(byte[] buffer, int start, int len) throws IOException {
		/* process all already read bytes */

		int n = is.read(buffer, start, len);
		if (n < 0) {
			close();
			onDisconnect();
			throw new IOException("Remote end closed connection.");
		}

		return n;
	}

	@Override
	public void write(byte[] buffer) throws IOException {
		try {
			if (os != null)
				os.write(buffer);
		} catch (SocketException e) {
			bridge.dispatchDisconnect(false);
		}
	}

	@Override
	public void write(int c) throws IOException {
		try {
			if (os != null)
				os.write(c);
		} catch (SocketException e) {
			bridge.dispatchDisconnect(false);
		}
	}

	@Override
	public void setDimensions(int columns, int rows, int width, int height) {
		//try {
			//handler.setWindowSize(columns, rows);
			Log.i(TAG, "setDimensions not implemented");
		//} catch (IOException e) {
		//	Log.e(TAG, "Couldn't resize remote terminal", e);
		//}
	}

	@SuppressLint("DefaultLocale")
	@Override
	public String getDefaultNickname(String username, String hostname, int port) {
		if (port == DEFAULT_PORT) {
			return String.format("%s", hostname);
		} else {
			return String.format("%s:%d", hostname, port);
		}
	}

	public static Uri getUri(String input) {
		Matcher matcher = hostmask.matcher(input);

		if (!matcher.matches())
			return null;

		StringBuilder sb = new StringBuilder();

		sb.append(PROTOCOL)
				.append("://")
				.append(matcher.group(1));

		String portString = matcher.group(3);
		int port = DEFAULT_PORT;
		if (portString != null) {
			try {
				port = Integer.parseInt(portString);
				if (port < 1 || port > 60) {
					port = DEFAULT_PORT;
				}
			} catch (NumberFormatException nfe) {
				// Keep the default port
			}
		}

		if (port != DEFAULT_PORT) {
			sb.append(':');
			sb.append(port);
		}

		sb.append("/#")
				.append(Uri.encode(input));

		return Uri.parse(sb.toString());
	}

	@Override
	public HostBean createHost(Uri uri) {
		HostBean host = new HostBean();

		host.setProtocol(PROTOCOL);

		host.setHostname(uri.getHost());

		int port = uri.getPort();
		if (port < 0 || port > 60)
			port = DEFAULT_PORT;
		host.setPort(port);

		String nickname = uri.getFragment();
		if (nickname == null || nickname.length() == 0) {
			host.setNickname(getDefaultNickname(host.getUsername(),
					host.getHostname(), host.getPort()));
		} else {
			host.setNickname(uri.getFragment());
		}

		return host;
	}

	@Override
	public void getSelectionArgs(Uri uri, Map<String, String> selection) {
		selection.put(HostDatabase.FIELD_HOST_PROTOCOL, PROTOCOL);
		selection.put(HostDatabase.FIELD_HOST_NICKNAME, uri.getFragment());
		selection.put(HostDatabase.FIELD_HOST_HOSTNAME, uri.getHost());

		int port = uri.getPort();
		if (port < 0 || port > 65535)
			port = DEFAULT_PORT;
		selection.put(HostDatabase.FIELD_HOST_PORT, Integer.toString(port));
	}

	public static String getFormatHint(Context context) {
		return String.format("%s:%s",
				context.getString(R.string.format_hostname),
				context.getString(R.string.format_port));
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#usesNetwork()
	 */
	@Override
	public boolean usesNetwork() {
		return true;
	}
}
