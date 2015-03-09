package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 *
 * @author stevko
 */
public class GroupMessengerActivity extends Activity {

	static final int SERVER_PORT = 10000;
	static final String[] REMOTE_PORT_ARRAY = {"11108", "11112", "11116", "11120", "11124"};

	private static final String CONTENT_PROVIDER_KEY = "key";
	private static final String CONTENT_PROVIDER_VALUE = "value";

	private static final String SEPARATOR = ":";
	private static final String INITIAL = "INITIAL";
	private static final String PROPOSED = "PROPOSED";
	private static final String FINAL = "FINAL";

	private String myPort;

	private PriorityQueue<Message> deliveryQueue;
	private HashMap<String, Message> deliveryMap;
	private Set<String> killedAVDs;

	private Integer proposalSeq = 0;
	private Integer contentProviderSeq = 0;
	private ContentResolver mContentResolver;
	private Uri mUri;

	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_group_messenger);

		TextView tv = (TextView) findViewById(R.id.textView1);
		tv.setMovementMethod(new ScrollingMovementMethod());

		findViewById(R.id.button1).setOnClickListener(
				new OnPTestClickListener(tv, getContentResolver()));

		TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
		String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
		myPort = String.valueOf((Integer.parseInt(portStr) * 2));

		Log.d(getCurrentTag(), portStr);
		Log.d(getCurrentTag(), myPort);

		try {
		    /*
		     * Create a server socket as well as a thread (AsyncTask) that listens on the server
             * port.
             *
             * AsyncTask is a simplified thread construct that Android provides. Please make sure
             * you know how it works by reading
             * http://developer.android.com/reference/android/os/AsyncTask.html
             */
			ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
			new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
		} catch (IOException e) {
			Log.e(getCurrentTag(), "IOException", e);
			return;
		}

		mUri = buildUri("content", "edu.buffalo.cse.cse486586.groupmessenger2.provider");
		mContentResolver = getContentResolver();

		final EditText editText = (EditText) findViewById(R.id.editText1);
		final Button sendButton = (Button) findViewById(R.id.button4);

		deliveryMap = new HashMap<>();
		deliveryQueue = new PriorityQueue<>();
		killedAVDs = new HashSet<>(1);

		sendButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {

				String msg = editText.getText().toString() + "\n";
				editText.setText(""); // This is one way to reset the input box.
				TextView localTextView = (TextView) findViewById(R.id.textView1);
				localTextView.append("->" + msg); // This is one way to display a string.

				proposalSeq++;
				msg = msg.replace("\n", "");
				String msgWithHeader = proposalSeq + "." + myPort + SEPARATOR + INITIAL + SEPARATOR + msg;

                /*
                 * Note that the following AsyncTask uses AsyncTask.SERIAL_EXECUTOR, not
                 * AsyncTask.THREAD_POOL_EXECUTOR as the above ServerTask does.
                 */
				for (String port : REMOTE_PORT_ARRAY) {
					if (!port.equalsIgnoreCase(myPort))
						new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msgWithHeader, port);
					else {
						Message m = new Message(msg, port, Double.parseDouble(proposalSeq + "." + myPort), 1);
						deliveryMap.put(msg, m);
						deliveryQueue.add(m);
					}
				}
			}
		});
	}

	private String getCurrentTag() {
		String tag = "";
		StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
		for (int i = 0; i < stackTraceElements.length; i++) {
			if (stackTraceElements[i].getMethodName().equals("getCurrentTag")) {
				tag = stackTraceElements[i + 1].getMethodName();
			}
		}
		return tag;
	}

	private Uri buildUri(String scheme, String authority) {
		Uri.Builder uriBuilder = new Uri.Builder();
		uriBuilder.authority(authority);
		uriBuilder.scheme(scheme);
		return uriBuilder.build();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
		return true;
	}

	private void createSocketAndWrite(String msg, String port) {
		try (Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
				Integer.parseInt(port));
		     PrintWriter clientOut =
				     new PrintWriter(socket.getOutputStream(), true)) {
			clientOut.println(msg);
		} catch (UnknownHostException e) {
			Log.e(getCurrentTag(), "UnknownHostException", e);
			killedAVDs.add(port);
		} catch (IOException e) {
			Log.e(getCurrentTag(), "IOException", e);
			killedAVDs.add(port);
		}
	}

	/**
	 * ServerTask is an AsyncTask that should handle incoming messages. It is created by
	 * ServerTask.executeOnExecutor() call in SimpleMessengerActivity.
	 */
	private class ServerTask extends AsyncTask<ServerSocket, String, Void> {

		@Override
		protected Void doInBackground(ServerSocket... sockets) {
			ServerSocket serverSocket = sockets[0];
			try {
                /*
                 * Server code that receives messages and passes them
                 * to onProgressUpdate().
                 */

				Pattern p = Pattern.compile("((.*)\\.(.*)):(.*):(.*)");
				Matcher matcher;
				Socket client;
				while ((client = serverSocket.accept()) != null) {
					Log.i(getCurrentTag(), "Client - " + client.getPort() + " made a request");
					BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));

					String inputLine;
					while ((inputLine = br.readLine()) != null) {
						matcher = p.matcher(inputLine);
						if (matcher.find()) {
							String receivedPriorityString = matcher.group(1);
							String receivedPortNumber = matcher.group(3);
							String receivedMsgType = matcher.group(4);
							String receivedMsg = matcher.group(5);
							Double receivedPriorityDouble = Double.parseDouble(receivedPriorityString);

							switch (receivedMsgType) {
								case "INITIAL": {
									Log.i(getCurrentTag(), "Got the initial request from " + receivedPortNumber + " with msg - " + receivedMsg);
									String msgToSend = ++proposalSeq + "." + myPort + SEPARATOR + PROPOSED + SEPARATOR + receivedMsg;

									Message message = new Message(receivedMsg, receivedPortNumber, Double.valueOf(proposalSeq + "." + myPort), 2);
									deliveryQueue.add(message);
									deliveryMap.put(message.msg, message);

									Log.d(getCurrentTag(), "ServerTask.doInBackground() - Sending proposal " + message.priority
											+ " for msg " + receivedMsg + " to " + receivedPortNumber);

									createSocketAndWrite(msgToSend, receivedPortNumber);
								}
								break;
								case "PROPOSED": {
									Log.i(getCurrentTag(), "Got the proposal from " + receivedPortNumber + " for msg - " + receivedMsg);
									Message proposedMsg = deliveryMap.get(receivedMsg);

									if (receivedPriorityDouble > proposedMsg.priority) {
										proposedMsg.priority = receivedPriorityDouble;
									}
									proposedMsg.replyCounter++;

									deliveryQueue.remove(proposedMsg);
									deliveryQueue.add(proposedMsg);

									if (proposedMsg.isDeliverable()) {

										String msgToSend = proposedMsg.priority + SEPARATOR + FINAL + SEPARATOR + proposedMsg.msg;
										for (String port : REMOTE_PORT_ARRAY) {
											if (!port.equals(myPort)) {
												Log.i(getCurrentTag(), "Got all proposals. Sending final msg " + proposedMsg.msg + " with priority " + proposedMsg.priority + " to " + port);
												createSocketAndWrite(msgToSend, port);
											}
										}
									}
									processQueue();
								}
								break;
								case "FINAL": {
									Log.i(getCurrentTag(), "Got the final confirmation from " + receivedPortNumber + " for msg - " + receivedMsg + " with priority " + receivedPriorityDouble);
									Message message = deliveryMap.get(receivedMsg);
									message.priority = receivedPriorityDouble;
									message.replyCounter = 5;

									deliveryQueue.remove(message);
									deliveryQueue.add(message);
									processQueue();
								}
							}
						}
					}
				}

			} catch (IOException e) {
				Log.e(getCurrentTag(), "IOException", e);
			}

			return null;
		}

		private void processQueue() {
			while ((deliveryQueue.peek() != null && deliveryQueue.peek().isDeliverable()) && deliveryQueue.peek().priority <= Double.parseDouble(proposalSeq + "." + REMOTE_PORT_ARRAY[4]))
			{
				Message topMessage = deliveryQueue.remove();
				deliveryMap.remove(topMessage.msg);
				publishProgress(topMessage.msg);
			}
		}

		protected void onProgressUpdate(String... strings) {

			Log.i("ContentProvider", "Writing to provider - " + strings[0] + " seq no. " + contentProviderSeq);

            /*
             * The following code displays what is received in doInBackground().
             */
			String strReceived = strings[0].trim();
			TextView localTextView = (TextView) findViewById(R.id.textView1);
			localTextView.append(strReceived + "\n");


            /*
             * The following code creates a file in the AVD's internal storage and stores a file.
             *
             * For more information on file I/O on Android, please take a look at
             * http://developer.android.com/training/basics/data-storage/files.html
             */

			ContentValues mContentValues = new ContentValues();
			mContentValues.put(CONTENT_PROVIDER_KEY, (contentProviderSeq++).toString());
			mContentValues.put(CONTENT_PROVIDER_VALUE, strReceived);

			mContentResolver.insert(mUri, mContentValues);
		}
	}

	/**
	 * ClientTask is an AsyncTask that sends a string over the network.
	 * It is created by ClientTask.executeOnExecutor() call whenever send button is clicked
	 */
	private class ClientTask extends AsyncTask<String, Void, Void> {

		@Override
		protected Void doInBackground(String... msgs) {
			String port = msgs[1];
			String msgToSend = msgs[0].replace("\n", "");

			Log.d(getCurrentTag(), "ClientTask.doInBackground() - Sending msg -" + msgToSend + " to " + port);
			createSocketAndWrite(msgToSend, port);

			return null;
		}
	}

	private class Message implements Comparable<Message> {
		Double priority;
		String msg;
		String msgOwner;
		int replyCounter;

		Message(String msg, String msgOwner, Double priority, int receiveCounter) {
			this.priority = priority;
			this.msg = msg;
			this.replyCounter = receiveCounter;
			this.msgOwner = msgOwner;
		}

		boolean isDeliverable() {
			//if the counter is reached or if this belongs to the killed AVD
			return (replyCounter == 5 - killedAVDs.size()) || killedAVDs.contains(msgOwner);
		}

		@Override
		public int compareTo(Message another) {
			return this.priority.compareTo(another.priority);
		}

		@Override
		public boolean equals(Object other) {
			return (other == this) || (other != null && (other instanceof Message) && this.msg.equals(((Message) other).msg));
		}

		@Override
		public String toString() {
			return super.toString();
		}
	}

}

/*
Messages retrieved from emulator-5554: {'24': 'u00fnvAnNkCem1zXQPB5DP5T6v4gXgQt', '20': 'b7OnePQ2r0omGoK6nceHwquvGfbw3wv2', '21': 'RsOuGmKfTP4hoPjSWcJPht0iWr4CnJsM', '22': 'VsjW9QrmAyRWQl8z5VtSGGxltlkPvmlc', '23': 'wKgQxnKmrjiFe62Ve4K9Gg2yrGf9xOLD', '1': 'NKNtVPHejZ2J8omlAVV97qOQNs4oV66I', '0': 'F9MsWlm0vrP6eZ4GbHEPeGySdgWPnGTn', '3': 'y7NNwujEAZknUZR2HpSCqUqcvHl54ef7', '2': 'BHRXBZmnE9P4tor2avySeG4F8R91cgVh', '5': 'dVSwejh1nLnKDam1GDXmJKKeXrIGikFV', '4': 'PDVtdai5YYibEhDDGibDZEj0cpREjOpZ', '7': 'd0hMyM2OQqV2PJey46x5cNDXZMiQ4vyC', '6': '7JbXpZlTOfBldvZxH7nPZSnXvURt4b31', '9': 'ZRylIV3zwqQwWLv69gTANI2EQz6xOthf', '8': 'kbc4ZAch4RqeTAVR3XtHtrZaMj2TjzKD', '11': '3FLmnb7xUvcxN3btJOWuOEO6UW5DIkZd', '10': 'PCaW06JVFmzmnjnEdV8ygWaHZReBj8wv', '13': 'FOxCLN4HoEsUJckGgRvc5m5AWup4QtWV', '12': 'Ztdpj2pDC93R3Xk8GvzGRavpbIGkcKz6', '15': 'm3q5k1LHX2Ff0CzOh4AziMJ1Hz22m079', '14': '7oYJ3wlFFCFqkNQ5vdzjbKrL6MV4Txix', '17': 'W1ymbaDiyVkBuRRpnBsIzstq3Tv4EhTg', '16': 'lpOEdx9xpStOppfC8X710zrRvRWhhBHP', '19': 'YAcQw1ysBi6SWXqn1R9wNeXQJPwKpFTl', '18': 'XKQcNvugqMWvqntlROPMDH2EEQara2qj'}
Messages retrieved from emulator-5556: {'24': 'u00fnvAnNkCem1zXQPB5DP5T6v4gXgQt', '20': 'b7OnePQ2r0omGoK6nceHwquvGfbw3wv2', '21': 'RsOuGmKfTP4hoPjSWcJPht0iWr4CnJsM', '22': 'VsjW9QrmAyRWQl8z5VtSGGxltlkPvmlc', '23': 'wKgQxnKmrjiFe62Ve4K9Gg2yrGf9xOLD', '1': 'NKNtVPHejZ2J8omlAVV97qOQNs4oV66I', '0': 'F9MsWlm0vrP6eZ4GbHEPeGySdgWPnGTn', '3': 'y7NNwujEAZknUZR2HpSCqUqcvHl54ef7', '2': 'BHRXBZmnE9P4tor2avySeG4F8R91cgVh', '5': 'dVSwejh1nLnKDam1GDXmJKKeXrIGikFV', '4': 'PDVtdai5YYibEhDDGibDZEj0cpREjOpZ', '7': 'd0hMyM2OQqV2PJey46x5cNDXZMiQ4vyC', '6': '7JbXpZlTOfBldvZxH7nPZSnXvURt4b31', '9': 'ZRylIV3zwqQwWLv69gTANI2EQz6xOthf', '8': 'kbc4ZAch4RqeTAVR3XtHtrZaMj2TjzKD', '11': '3FLmnb7xUvcxN3btJOWuOEO6UW5DIkZd', '10': 'PCaW06JVFmzmnjnEdV8ygWaHZReBj8wv', '13': 'FOxCLN4HoEsUJckGgRvc5m5AWup4QtWV', '12': 'Ztdpj2pDC93R3Xk8GvzGRavpbIGkcKz6', '15': 'm3q5k1LHX2Ff0CzOh4AziMJ1Hz22m079', '14': '7oYJ3wlFFCFqkNQ5vdzjbKrL6MV4Txix', '17': 'W1ymbaDiyVkBuRRpnBsIzstq3Tv4EhTg', '16': 'lpOEdx9xpStOppfC8X710zrRvRWhhBHP', '19': 'YAcQw1ysBi6SWXqn1R9wNeXQJPwKpFTl', '18': 'XKQcNvugqMWvqntlROPMDH2EEQara2qj'}
Messages retrieved from emulator-5558: {'24': 'u00fnvAnNkCem1zXQPB5DP5T6v4gXgQt', '20': 'b7OnePQ2r0omGoK6nceHwquvGfbw3wv2', '21': 'RsOuGmKfTP4hoPjSWcJPht0iWr4CnJsM', '22': 'VsjW9QrmAyRWQl8z5VtSGGxltlkPvmlc', '23': 'wKgQxnKmrjiFe62Ve4K9Gg2yrGf9xOLD', '1': 'NKNtVPHejZ2J8omlAVV97qOQNs4oV66I', '0': 'F9MsWlm0vrP6eZ4GbHEPeGySdgWPnGTn', '3': 'y7NNwujEAZknUZR2HpSCqUqcvHl54ef7', '2': 'BHRXBZmnE9P4tor2avySeG4F8R91cgVh', '5': 'dVSwejh1nLnKDam1GDXmJKKeXrIGikFV', '4': 'PDVtdai5YYibEhDDGibDZEj0cpREjOpZ', '7': 'd0hMyM2OQqV2PJey46x5cNDXZMiQ4vyC', '6': '7JbXpZlTOfBldvZxH7nPZSnXvURt4b31', '9': 'ZRylIV3zwqQwWLv69gTANI2EQz6xOthf', '8': 'kbc4ZAch4RqeTAVR3XtHtrZaMj2TjzKD', '11': '3FLmnb7xUvcxN3btJOWuOEO6UW5DIkZd', '10': 'PCaW06JVFmzmnjnEdV8ygWaHZReBj8wv', '13': 'FOxCLN4HoEsUJckGgRvc5m5AWup4QtWV', '12': 'Ztdpj2pDC93R3Xk8GvzGRavpbIGkcKz6', '15': 'm3q5k1LHX2Ff0CzOh4AziMJ1Hz22m079', '14': '7oYJ3wlFFCFqkNQ5vdzjbKrL6MV4Txix', '17': 'W1ymbaDiyVkBuRRpnBsIzstq3Tv4EhTg', '16': 'lpOEdx9xpStOppfC8X710zrRvRWhhBHP', '19': 'YAcQw1ysBi6SWXqn1R9wNeXQJPwKpFTl', '18': 'XKQcNvugqMWvqntlROPMDH2EEQara2qj'}
Messages retrieved from emulator-5560: {'24': 'wKgQxnKmrjiFe62Ve4K9Gg2yrGf9xOLD', '20': 'b7OnePQ2r0omGoK6nceHwquvGfbw3wv2', '21': 'RsOuGmKfTP4hoPjSWcJPht0iWr4CnJsM', '22': 'VsjW9QrmAyRWQl8z5VtSGGxltlkPvmlc', '23': 'u00fnvAnNkCem1zXQPB5DP5T6v4gXgQt', '1': 'NKNtVPHejZ2J8omlAVV97qOQNs4oV66I', '0': 'F9MsWlm0vrP6eZ4GbHEPeGySdgWPnGTn', '3': 'y7NNwujEAZknUZR2HpSCqUqcvHl54ef7', '2': 'BHRXBZmnE9P4tor2avySeG4F8R91cgVh', '5': 'dVSwejh1nLnKDam1GDXmJKKeXrIGikFV', '4': 'PDVtdai5YYibEhDDGibDZEj0cpREjOpZ', '7': 'd0hMyM2OQqV2PJey46x5cNDXZMiQ4vyC', '6': '7JbXpZlTOfBldvZxH7nPZSnXvURt4b31', '9': 'ZRylIV3zwqQwWLv69gTANI2EQz6xOthf', '8': 'kbc4ZAch4RqeTAVR3XtHtrZaMj2TjzKD', '11': '3FLmnb7xUvcxN3btJOWuOEO6UW5DIkZd', '10': 'PCaW06JVFmzmnjnEdV8ygWaHZReBj8wv', '13': 'FOxCLN4HoEsUJckGgRvc5m5AWup4QtWV', '12': 'Ztdpj2pDC93R3Xk8GvzGRavpbIGkcKz6', '15': 'm3q5k1LHX2Ff0CzOh4AziMJ1Hz22m079', '14': '7oYJ3wlFFCFqkNQ5vdzjbKrL6MV4Txix', '17': 'W1ymbaDiyVkBuRRpnBsIzstq3Tv4EhTg', '16': 'lpOEdx9xpStOppfC8X710zrRvRWhhBHP', '19': 'YAcQw1ysBi6SWXqn1R9wNeXQJPwKpFTl', '18': 'XKQcNvugqMWvqntlROPMDH2EEQara2qj'}
Messages retrieved from emulator-5562: {'24': 'u00fnvAnNkCem1zXQPB5DP5T6v4gXgQt', '20': 'b7OnePQ2r0omGoK6nceHwquvGfbw3wv2', '21': 'RsOuGmKfTP4hoPjSWcJPht0iWr4CnJsM', '22': 'VsjW9QrmAyRWQl8z5VtSGGxltlkPvmlc', '23': 'wKgQxnKmrjiFe62Ve4K9Gg2yrGf9xOLD', '1': 'NKNtVPHejZ2J8omlAVV97qOQNs4oV66I', '0': 'F9MsWlm0vrP6eZ4GbHEPeGySdgWPnGTn', '3': 'y7NNwujEAZknUZR2HpSCqUqcvHl54ef7', '2': 'BHRXBZmnE9P4tor2avySeG4F8R91cgVh', '5': 'dVSwejh1nLnKDam1GDXmJKKeXrIGikFV', '4': 'PDVtdai5YYibEhDDGibDZEj0cpREjOpZ', '7': 'd0hMyM2OQqV2PJey46x5cNDXZMiQ4vyC', '6': '7JbXpZlTOfBldvZxH7nPZSnXvURt4b31', '9': 'ZRylIV3zwqQwWLv69gTANI2EQz6xOthf', '8': 'kbc4ZAch4RqeTAVR3XtHtrZaMj2TjzKD', '11': '3FLmnb7xUvcxN3btJOWuOEO6UW5DIkZd', '10': 'PCaW06JVFmzmnjnEdV8ygWaHZReBj8wv', '13': 'FOxCLN4HoEsUJckGgRvc5m5AWup4QtWV', '12': 'Ztdpj2pDC93R3Xk8GvzGRavpbIGkcKz6', '15': 'm3q5k1LHX2Ff0CzOh4AziMJ1Hz22m079', '14': '7oYJ3wlFFCFqkNQ5vdzjbKrL6MV4Txix', '17': 'W1ymbaDiyVkBuRRpnBsIzstq3Tv4EhTg', '16': 'lpOEdx9xpStOppfC8X710zrRvRWhhBHP', '19': 'YAcQw1ysBi6SWXqn1R9wNeXQJPwKpFTl', '18': 'XKQcNvugqMWvqntlROPMDH2EEQara2qj'}

Run 2

(AVD2) Messages retrieved from emulator-5554: {'24': '8rUTK1GPI1Ql2n119Om2zI2knfpmcnQe', '20': 'SzQ2IYIL4k9Zy9ZbAXQMv3KSRvvWNxO1', '21': 'jF398akhLOwU1QtE2ciSjR8SuBwu5tSJ', '22': 'ySTxichOOeSDo56tSxJpSekhFZF0Oixo', '23': 'rLo3Op5EIYSNOft3tlwtPVM8jaX901GY', '1':             (AVD2) 5554 'nPOjPdGwQ7OD060ubg6vTJBXJZWfvMLE', '0': 'gmsJi7emEYzgS3RDtCC0wJ6Glyxb5Oq2', '3': 'ndNKIEY3iKIavK78d2J7rEAUJYZlIXHQ', '2': '0kyDdXWCPCUjjG15SLqha29PjTcm9LHp', '5': '5bkrCqtAyjA8dXFxdGsLsRFmAFwK859x', '4': 'FJw3DS9ZAgLBc3Mriktz7pYEbvxk8HZZ', '7': 'B4e2MflvEND22rU2Zg1CTddEI2qmeO67', '6': 'goaXNuehXGPGVOPLUIrfiJLKgcJ7rZbF', '9': 'INEThN3badf9MOLYJRByDoifukQF8i04', '8': '9mNEUQzu3N8TlDhHwJTqLYy0NYjQMTBC', '11': 'ATL2TOTBqRxzkvYEw3eNgMygkx3cWEJ', '10': 'xSYSqxyfWnsWtQMucuQR9KihoXKDmwUq', '13': 'QWfRLzkscuVXaqUXOyqoTFV2Hg8Dtns4', '12': 'EMQc3IsYVdLB3GFZU3tSyvQHyqQ3OOxP', '15': 'ydXLfI3uO5FSqH2jNEYSJ5Vt3tAOXKrz', '14': 'FVUgaySOFdnDGcunm7hoAPOSegaRXaj1', '17': '0X6WDNOmgUTGwbJFg25vgJvHeY1kT6gf', '16': 'AjwVJLUpdOKYvm1u8G9UcLdZpnw9u1g3', '19': 'nbV1PighAvQBTclLLG3hI7S9y7hejS7a', '18': 'EB30CMR2DfDsI1vUFVJAEpEZydJkQyrb'}
(AVD4) Messages retrieved from emulator-5556: {'24': '8rUTK1GPI1Ql2n119Om2zI2knfpmcnQe', '20': 'SzQ2IYIL4k9Zy9ZbAXQMv3KSRvvWNxO1', '21': 'jF398akhLOwU1QtE2ciSjR8SuBwu5tSJ', '22': 'ySTxichOOeSDo56tSxJpSekhFZF0Oixo', '23': 'rLo3Op5EIYSNOft3tlwtPVM8jaX901GY', '1': Orig - Orig (AVD4) 5556 'nPOjPdGwQ7OD060ubg6vTJBXJZWfvMLE', '0': 'gmsJi7emEYzgS3RDtCC0wJ6Glyxb5Oq2', '3': 'ndNKIEY3iKIavK78d2J7rEAUJYZlIXHQ', '2': '0kyDdXWCPCUjjG15SLqha29PjTcm9LHp', '5': '5bkrCqtAyjA8dXFxdGsLsRFmAFwK859x', '4': 'FJw3DS9ZAgLBc3Mriktz7pYEbvxk8HZZ', '7': 'B4e2MflvEND22rU2Zg1CTddEI2qmeO67', '6': 'goaXNuehXGPGVOPLUIrfiJLKgcJ7rZbF', '9': 'INEThN3badf9MOLYJRByDoifukQF8i04', '8': '9mNEUQzu3N8TlDhHwJTqLYy0NYjQMTBC', '11': 'ATL2TOTBqRxzkvYEw3eNgMygkx3cWEJ', '10': 'xSYSqxyfWnsWtQMucuQR9KihoXKDmwUq', '13': 'QWfRLzkscuVXaqUXOyqoTFV2Hg8Dtns4', '12': 'EMQc3IsYVdLB3GFZU3tSyvQHyqQ3OOxP', '15': 'ydXLfI3uO5FSqH2jNEYSJ5Vt3tAOXKrz', '14': 'FVUgaySOFdnDGcunm7hoAPOSegaRXaj1', '17': '0X6WDNOmgUTGwbJFg25vgJvHeY1kT6gf', '16': 'AjwVJLUpdOKYvm1u8G9UcLdZpnw9u1g3', '19': 'nbV1PighAvQBTclLLG3hI7S9y7hejS7a', '18': 'EB30CMR2DfDsI1vUFVJAEpEZydJkQyrb'}
(AVD3) Messages retrieved from emulator-5558: {'24': '8rUTK1GPI1Ql2n119Om2zI2knfpmcnQe', '20': 'SzQ2IYIL4k9Zy9ZbAXQMv3KSRvvWNxO1', '21': 'jF398akhLOwU1QtE2ciSjR8SuBwu5tSJ', '22': 'ySTxichOOeSDo56tSxJpSekhFZF0Oixo', '23': 'rLo3Op5EIYSNOft3tlwtPVM8jaX901GY', '1':             (AVD3) 5558 '0kyDdXWCPCUjjG15SLqha29PjTcm9LHp', '0': 'gmsJi7emEYzgS3RDtCC0wJ6Glyxb5Oq2', '3': 'ndNKIEY3iKIavK78d2J7rEAUJYZlIXHQ', '2': 'nPOjPdGwQ7OD060ubg6vTJBXJZWfvMLE', '5': '5bkrCqtAyjA8dXFxdGsLsRFmAFwK859x', '4': 'FJw3DS9ZAgLBc3Mriktz7pYEbvxk8HZZ', '7': 'B4e2MflvEND22rU2Zg1CTddEI2qmeO67', '6': 'goaXNuehXGPGVOPLUIrfiJLKgcJ7rZbF', '9': 'INEThN3badf9MOLYJRByDoifukQF8i04', '8': '9mNEUQzu3N8TlDhHwJTqLYy0NYjQMTBC', '11': 'ATL2TOTBqRxzkvYEw3eNgMygkx3cWEJ', '10': 'xSYSqxyfWnsWtQMucuQR9KihoXKDmwUq', '13': 'QWfRLzkscuVXaqUXOyqoTFV2Hg8Dtns4', '12': 'EMQc3IsYVdLB3GFZU3tSyvQHyqQ3OOxP', '15': 'ydXLfI3uO5FSqH2jNEYSJ5Vt3tAOXKrz', '14': 'FVUgaySOFdnDGcunm7hoAPOSegaRXaj1', '17': '0X6WDNOmgUTGwbJFg25vgJvHeY1kT6gf', '16': 'AjwVJLUpdOKYvm1u8G9UcLdZpnw9u1g3', '19': 'nbV1PighAvQBTclLLG3hI7S9y7hejS7a', '18': 'EB30CMR2DfDsI1vUFVJAEpEZydJkQyrb'}
(AVD1) Messages retrieved from emulator-5560: {'24': '8rUTK1GPI1Ql2n119Om2zI2knfpmcnQe', '20': 'SzQ2IYIL4k9Zy9ZbAXQMv3KSRvvWNxO1', '21': 'jF398akhLOwU1QtE2ciSjR8SuBwu5tSJ', '22': 'ySTxichOOeSDo56tSxJpSekhFZF0Oixo', '23': 'rLo3Op5EIYSNOft3tlwtPVM8jaX901GY', '1':             (AVD1) 5560 '0kyDdXWCPCUjjG15SLqha29PjTcm9LHp', '0': 'gmsJi7emEYzgS3RDtCC0wJ6Glyxb5Oq2', '3': 'ndNKIEY3iKIavK78d2J7rEAUJYZlIXHQ', '2': 'nPOjPdGwQ7OD060ubg6vTJBXJZWfvMLE', '5': '5bkrCqtAyjA8dXFxdGsLsRFmAFwK859x', '4': 'FJw3DS9ZAgLBc3Mriktz7pYEbvxk8HZZ', '7': 'B4e2MflvEND22rU2Zg1CTddEI2qmeO67', '6': 'goaXNuehXGPGVOPLUIrfiJLKgcJ7rZbF', '9': 'INEThN3badf9MOLYJRByDoifukQF8i04', '8': '9mNEUQzu3N8TlDhHwJTqLYy0NYjQMTBC', '11': 'ATL2TOTBqRxzkvYEw3eNgMygkx3cWEJ', '10': 'xSYSqxyfWnsWtQMucuQR9KihoXKDmwUq', '13': 'QWfRLzkscuVXaqUXOyqoTFV2Hg8Dtns4', '12': 'EMQc3IsYVdLB3GFZU3tSyvQHyqQ3OOxP', '15': 'ydXLfI3uO5FSqH2jNEYSJ5Vt3tAOXKrz', '14': 'FVUgaySOFdnDGcunm7hoAPOSegaRXaj1', '17': '0X6WDNOmgUTGwbJFg25vgJvHeY1kT6gf', '16': 'AjwVJLUpdOKYvm1u8G9UcLdZpnw9u1g3', '19': 'nbV1PighAvQBTclLLG3hI7S9y7hejS7a', '18': 'EB30CMR2DfDsI1vUFVJAEpEZydJkQyrb'}
(AVD0) Messages retrieved from emulator-5562: {'24': '8rUTK1GPI1Ql2n119Om2zI2knfpmcnQe', '20': 'SzQ2IYIL4k9Zy9ZbAXQMv3KSRvvWNxO1', '21': 'jF398akhLOwU1QtE2ciSjR8SuBwu5tSJ', '22': 'ySTxichOOeSDo56tSxJpSekhFZF0Oixo', '23': 'rLo3Op5EIYSNOft3tlwtPVM8jaX901GY', '1':             (AVD0) 5562 'nPOjPdGwQ7OD060ubg6vTJBXJZWfvMLE', '0': 'gmsJi7emEYzgS3RDtCC0wJ6Glyxb5Oq2', '3': 'ndNKIEY3iKIavK78d2J7rEAUJYZlIXHQ', '2': '0kyDdXWCPCUjjG15SLqha29PjTcm9LHp', '5': '5bkrCqtAyjA8dXFxdGsLsRFmAFwK859x', '4': 'FJw3DS9ZAgLBc3Mriktz7pYEbvxk8HZZ', '7': 'B4e2MflvEND22rU2Zg1CTddEI2qmeO67', '6': 'goaXNuehXGPGVOPLUIrfiJLKgcJ7rZbF', '9': 'INEThN3badf9MOLYJRByDoifukQF8i04', '8': '9mNEUQzu3N8TlDhHwJTqLYy0NYjQMTBC', '11': 'ATL2TOTBqRxzkvYEw3eNgMygkx3cWEJ', '10': 'xSYSqxyfWnsWtQMucuQR9KihoXKDmwUq', '13': 'QWfRLzkscuVXaqUXOyqoTFV2Hg8Dtns4', '12': 'EMQc3IsYVdLB3GFZU3tSyvQHyqQ3OOxP', '15': 'ydXLfI3uO5FSqH2jNEYSJ5Vt3tAOXKrz', '14': 'FVUgaySOFdnDGcunm7hoAPOSegaRXaj1', '17': '0X6WDNOmgUTGwbJFg25vgJvHeY1kT6gf', '16': 'AjwVJLUpdOKYvm1u8G9UcLdZpnw9u1g3', '19': 'nbV1PighAvQBTclLLG3hI7S9y7hejS7a', '18': 'EB30CMR2DfDsI1vUFVJAEpEZydJkQyrb'}
Verifying total ordering

Deadlock
Messages retrieved from emulator-5554: {'1': 'UMK807CsNufd4oi8NkbZMmhb8yFDuBfX', '0': 'MjKuJzFf5z0doIafesVepeklkEZnA2gK', '3': 'fJLIhgaJP5gfdJmxqhewPEgmzcD4THxd', '2': 'lYeHHjv6M0uVDlukFqBfLH1TKtcBKk57', '5': 'YJoDGxFxOlLXn5oz1UIejgDtn2eeFnAa', '4': 'Vumqha0wAQKMIGVr0vwXQ3crjCwlzUIh', '7': 'Nl69sq8SuZaZ6feusuWaloW4fnjhtzQW', '6': 'tPvETjloFm1zkeeDR3naSJHhtTYfZbWc', '8': '8AmQrPRZ7CYEYGiaXyWYS3ivXqVhLZpg'}
Messages retrieved from emulator-5556: {'1': 'UMK807CsNufd4oi8NkbZMmhb8yFDuBfX', '0': 'MjKuJzFf5z0doIafesVepeklkEZnA2gK', '3': 'fJLIhgaJP5gfdJmxqhewPEgmzcD4THxd', '2': 'lYeHHjv6M0uVDlukFqBfLH1TKtcBKk57', '5': 'YJoDGxFxOlLXn5oz1UIejgDtn2eeFnAa', '4': 'Vumqha0wAQKMIGVr0vwXQ3crjCwlzUIh', '7': 'Nl69sq8SuZaZ6feusuWaloW4fnjhtzQW', '6': 'tPvETjloFm1zkeeDR3naSJHhtTYfZbWc', '8': '8AmQrPRZ7CYEYGiaXyWYS3ivXqVhLZpg'}
Messages retrieved from emulator-5558: {'1': 'UMK807CsNufd4oi8NkbZMmhb8yFDuBfX', '0': 'MjKuJzFf5z0doIafesVepeklkEZnA2gK', '3': 'fJLIhgaJP5gfdJmxqhewPEgmzcD4THxd', '2': 'lYeHHjv6M0uVDlukFqBfLH1TKtcBKk57', '5': 'YJoDGxFxOlLXn5oz1UIejgDtn2eeFnAa', '4': 'Vumqha0wAQKMIGVr0vwXQ3crjCwlzUIh', '7': 'Nl69sq8SuZaZ6feusuWaloW4fnjhtzQW', '6': 'tPvETjloFm1zkeeDR3naSJHhtTYfZbWc', '9': 'iZwBahqr0cW9JEUi3uJNIEdQjFC95vjm', '8': '8AmQrPRZ7CYEYGiaXyWYS3ivXqVhLZpg'}
Messages retrieved from emulator-5560: {'11': 'j38CX6Q2TU8S1384Y9a5KLJVT23vOv2N', '10': '3od1d2NOEcdmQ6vHbHqzfjPjK4rqNxxK', '1': 'UMK807CsNufd4oi8NkbZMmhb8yFDuBfX', '0': 'MjKuJzFf5z0doIafesVepeklkEZnA2gK', '3': 'fJLIhgaJP5gfdJmxqhewPEgmzcD4THxd', '2': 'lYeHHjv6M0uVDlukFqBfLH1TKtcBKk57', '5': 'YJoDGxFxOlLXn5oz1UIejgDtn2eeFnAa', '4': 'Vumqha0wAQKMIGVr0vwXQ3crjCwlzUIh', '7': 'Nl69sq8SuZaZ6feusuWaloW4fnjhtzQW', '6': 'tPvETjloFm1zkeeDR3naSJHhtTYfZbWc', '9': 'iZwBahqr0cW9JEUi3uJNIEdQjFC95vjm', '8': '8AmQrPRZ7CYEYGiaXyWYS3ivXqVhLZpg'}
Messages retrieved from emulator-5562: {'1': 'UMK807CsNufd4oi8NkbZMmhb8yFDuBfX', '0': 'MjKuJzFf5z0doIafesVepeklkEZnA2gK', '3': 'fJLIhgaJP5gfdJmxqhewPEgmzcD4THxd', '2': 'lYeHHjv6M0uVDlukFqBfLH1TKtcBKk57', '5': 'YJoDGxFxOlLXn5oz1UIejgDtn2eeFnAa', '4': 'Vumqha0wAQKMIGVr0vwXQ3crjCwlzUIh', '7': 'Nl69sq8SuZaZ6feusuWaloW4fnjhtzQW', '6': 'tPvETjloFm1zkeeDR3naSJHhtTYfZbWc', '8': '8AmQrPRZ7CYEYGiaXyWYS3ivXqVhLZpg'}


Messages retrieved from emulator-5554: {'20': 'ZPsK07gw7nu3QXRyxM2U5YttqY2UhA93', '21': 'zb6WRrFgcUHZrBo47Xf7t9qAKdNJlNWP', '22': 'UBZMx6DJeTAIGQJ6TOfqsUFNmhIuvf4N', '23': 'dhqhl3jBrI24HeYevP3ZAZSeI9bZi9eh', '1': 'MbeLSmaYj5AtIeVokd4W1w3a6ke2MtgX', '0': 'MEXoh2iF3S2OGGIf1AbZumNowyL8yTs1', '3': 'vwaatBjaudKezrNtfu9dkvebJ90mTwca', '2': '1Biag31JfawhjN1ovJmF98YQzPCpizx2', '5': 'sfjPIPY5VmP4EJzkDBmjp7GDpBVwKa3I', '4': 'R5XwEj3Md1ozub4VH4ADYXhGWkJbVEgB', '7': '4KAKA3KnxTaq7Am52wtyFucONMRw9Rq2', '6': 'BEKZfD1BUjn6DpiI4fSNDR3cLLrmMYAC', '9': 'BP4aBTfg1XRahv0kLyQgPNJ92eTEpT7a', '8': 'pFIdQLfrs6UV7PYV1AilgRZ9QJrRaxEi', '11': 'mw0MJgDfmtiXZBiJKXewJY9wG40REaBu', '10': 'ikGmBRsvN8ik7SMA2ZgSOWNBmLft7h4B', '13': 'M6kkMMtuHapAE3EztJz4BsiAkFqlXfCL', '12': 'U8WfahftDIsUi7v2WUZtUlStgJuBpZAC', '15': 'YJScUc2LB8ow3NkdbekcDNJ7cxWK2rk5', '14': 'MJP5Glf8Jo9w5EEhH7dHcxoRSBaTmsla', '17': 'IgUNW10azCxOTBHYGykznGa1Pr7eY8mA', '16': 'rBNKrYoUJaWO5TO2eYqqVPwDSVUNjcvz', '19': 'xYR0A12OQsIv18d19ql4EWocHGJgj7VS', '18': 'h48owvNM9hAwGDstgOUIjHZ7tlVx4D0o'}
Messages retrieved from emulator-5556: {'20': 'ZPsK07gw7nu3QXRyxM2U5YttqY2UhA93', '21': 'zb6WRrFgcUHZrBo47Xf7t9qAKdNJlNWP', '22': 'UBZMx6DJeTAIGQJ6TOfqsUFNmhIuvf4N', '23': 'dhqhl3jBrI24HeYevP3ZAZSeI9bZi9eh', '1': 'MbeLSmaYj5AtIeVokd4W1w3a6ke2MtgX', '0': 'MEXoh2iF3S2OGGIf1AbZumNowyL8yTs1', '3': 'vwaatBjaudKezrNtfu9dkvebJ90mTwca', '2': '1Biag31JfawhjN1ovJmF98YQzPCpizx2', '5': 'sfjPIPY5VmP4EJzkDBmjp7GDpBVwKa3I', '4': 'R5XwEj3Md1ozub4VH4ADYXhGWkJbVEgB', '7': '4KAKA3KnxTaq7Am52wtyFucONMRw9Rq2', '6': 'BEKZfD1BUjn6DpiI4fSNDR3cLLrmMYAC', '9': 'BP4aBTfg1XRahv0kLyQgPNJ92eTEpT7a', '8': 'pFIdQLfrs6UV7PYV1AilgRZ9QJrRaxEi', '11': 'mw0MJgDfmtiXZBiJKXewJY9wG40REaBu', '10': 'ikGmBRsvN8ik7SMA2ZgSOWNBmLft7h4B', '13': 'M6kkMMtuHapAE3EztJz4BsiAkFqlXfCL', '12': 'U8WfahftDIsUi7v2WUZtUlStgJuBpZAC', '15': 'YJScUc2LB8ow3NkdbekcDNJ7cxWK2rk5', '14': 'MJP5Glf8Jo9w5EEhH7dHcxoRSBaTmsla', '17': 'IgUNW10azCxOTBHYGykznGa1Pr7eY8mA', '16': 'rBNKrYoUJaWO5TO2eYqqVPwDSVUNjcvz', '19': 'xYR0A12OQsIv18d19ql4EWocHGJgj7VS', '18': 'h48owvNM9hAwGDstgOUIjHZ7tlVx4D0o'}
Messages retrieved from emulator-5558: {'20': 'ZPsK07gw7nu3QXRyxM2U5YttqY2UhA93', '21': 'zb6WRrFgcUHZrBo47Xf7t9qAKdNJlNWP', '22': 'UBZMx6DJeTAIGQJ6TOfqsUFNmhIuvf4N', '23': 'dhqhl3jBrI24HeYevP3ZAZSeI9bZi9eh', '1': 'MbeLSmaYj5AtIeVokd4W1w3a6ke2MtgX', '0': 'MEXoh2iF3S2OGGIf1AbZumNowyL8yTs1', '3': 'vwaatBjaudKezrNtfu9dkvebJ90mTwca', '2': '1Biag31JfawhjN1ovJmF98YQzPCpizx2', '5': 'sfjPIPY5VmP4EJzkDBmjp7GDpBVwKa3I', '4': 'R5XwEj3Md1ozub4VH4ADYXhGWkJbVEgB', '7': '4KAKA3KnxTaq7Am52wtyFucONMRw9Rq2', '6': 'BEKZfD1BUjn6DpiI4fSNDR3cLLrmMYAC', '9': 'BP4aBTfg1XRahv0kLyQgPNJ92eTEpT7a', '8': 'pFIdQLfrs6UV7PYV1AilgRZ9QJrRaxEi', '11': 'mw0MJgDfmtiXZBiJKXewJY9wG40REaBu', '10': 'ikGmBRsvN8ik7SMA2ZgSOWNBmLft7h4B', '13': 'M6kkMMtuHapAE3EztJz4BsiAkFqlXfCL', '12': 'U8WfahftDIsUi7v2WUZtUlStgJuBpZAC', '15': 'YJScUc2LB8ow3NkdbekcDNJ7cxWK2rk5', '14': 'MJP5Glf8Jo9w5EEhH7dHcxoRSBaTmsla', '17': 'IgUNW10azCxOTBHYGykznGa1Pr7eY8mA', '16': 'rBNKrYoUJaWO5TO2eYqqVPwDSVUNjcvz', '19': 'xYR0A12OQsIv18d19ql4EWocHGJgj7VS', '18': 'h48owvNM9hAwGDstgOUIjHZ7tlVx4D0o'}
Messages retrieved from emulator-5560: {'20': 'ZPsK07gw7nu3QXRyxM2U5YttqY2UhA93', '21': 'zb6WRrFgcUHZrBo47Xf7t9qAKdNJlNWP', '22': 'UBZMx6DJeTAIGQJ6TOfqsUFNmhIuvf4N', '23': 'dhqhl3jBrI24HeYevP3ZAZSeI9bZi9eh', '1': 'MbeLSmaYj5AtIeVokd4W1w3a6ke2MtgX', '0': 'MEXoh2iF3S2OGGIf1AbZumNowyL8yTs1', '3': 'vwaatBjaudKezrNtfu9dkvebJ90mTwca', '2': '1Biag31JfawhjN1ovJmF98YQzPCpizx2', '5': 'sfjPIPY5VmP4EJzkDBmjp7GDpBVwKa3I', '4': 'R5XwEj3Md1ozub4VH4ADYXhGWkJbVEgB', '7': '4KAKA3KnxTaq7Am52wtyFucONMRw9Rq2', '6': 'BEKZfD1BUjn6DpiI4fSNDR3cLLrmMYAC', '9': 'BP4aBTfg1XRahv0kLyQgPNJ92eTEpT7a', '8': 'pFIdQLfrs6UV7PYV1AilgRZ9QJrRaxEi', '11': 'mw0MJgDfmtiXZBiJKXewJY9wG40REaBu', '10': 'ikGmBRsvN8ik7SMA2ZgSOWNBmLft7h4B', '13': 'M6kkMMtuHapAE3EztJz4BsiAkFqlXfCL', '12': 'U8WfahftDIsUi7v2WUZtUlStgJuBpZAC', '15': 'YJScUc2LB8ow3NkdbekcDNJ7cxWK2rk5', '14': 'MJP5Glf8Jo9w5EEhH7dHcxoRSBaTmsla', '17': 'IgUNW10azCxOTBHYGykznGa1Pr7eY8mA', '16': 'rBNKrYoUJaWO5TO2eYqqVPwDSVUNjcvz', '19': 'xYR0A12OQsIv18d19ql4EWocHGJgj7VS', '18': 'h48owvNM9hAwGDstgOUIjHZ7tlVx4D0o'}
Messages retrieved from emulator-5562: {'20': 'ZPsK07gw7nu3QXRyxM2U5YttqY2UhA93', '21': 'zb6WRrFgcUHZrBo47Xf7t9qAKdNJlNWP', '22': 'UBZMx6DJeTAIGQJ6TOfqsUFNmhIuvf4N', '23': 'dhqhl3jBrI24HeYevP3ZAZSeI9bZi9eh', '1': 'MbeLSmaYj5AtIeVokd4W1w3a6ke2MtgX', '0': 'MEXoh2iF3S2OGGIf1AbZumNowyL8yTs1', '3': 'vwaatBjaudKezrNtfu9dkvebJ90mTwca', '2': '1Biag31JfawhjN1ovJmF98YQzPCpizx2', '5': 'sfjPIPY5VmP4EJzkDBmjp7GDpBVwKa3I', '4': 'R5XwEj3Md1ozub4VH4ADYXhGWkJbVEgB', '7': '4KAKA3KnxTaq7Am52wtyFucONMRw9Rq2', '6': 'BEKZfD1BUjn6DpiI4fSNDR3cLLrmMYAC', '9': 'BP4aBTfg1XRahv0kLyQgPNJ92eTEpT7a', '8': 'pFIdQLfrs6UV7PYV1AilgRZ9QJrRaxEi', '11': 'mw0MJgDfmtiXZBiJKXewJY9wG40REaBu', '10': 'ikGmBRsvN8ik7SMA2ZgSOWNBmLft7h4B', '13': 'M6kkMMtuHapAE3EztJz4BsiAkFqlXfCL', '12': 'U8WfahftDIsUi7v2WUZtUlStgJuBpZAC', '15': 'YJScUc2LB8ow3NkdbekcDNJ7cxWK2rk5', '14': 'MJP5Glf8Jo9w5EEhH7dHcxoRSBaTmsla', '17': 'IgUNW10azCxOTBHYGykznGa1Pr7eY8mA', '16': 'rBNKrYoUJaWO5TO2eYqqVPwDSVUNjcvz', '19': 'xYR0A12OQsIv18d19ql4EWocHGJgj7VS', '18': 'h48owvNM9hAwGDstgOUIjHZ7tlVx4D0o'}

*/