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
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 *
 * @author stevko
 */
public class GroupMessengerActivity extends Activity {
    static final String TAG = GroupMessengerActivity.class.getSimpleName();
    static final String REMOTE_PORT0 = "11108";
    static final String REMOTE_PORT1 = "11112";
    static final String REMOTE_PORT2 = "11116";
    static final String REMOTE_PORT3 = "11120";
    static final String REMOTE_PORT4 = "11124";

    static final String[] REMOTE_PORT_ARRAY = {"11108", "11112", "11116", "11120", "11124"};
    static final int SERVER_PORT = 10000;

    private static final String KEY_FIELD = "key";
    private static final String VALUE_FIELD = "value";

    private static final String INITIAL = "INITIAL";
    private static final String PROPOSED = "PROPOSED";
    private static final String FINAL = "FINAL";

    private static final String SEPERATOR = ":";
    String portStr;
    String myPort;
    PriorityQueue<Message> deliveryQueue = new PriorityQueue<>();
    private int seq;
    private HashMap<String, Message> map;

    private Integer contentProviderSeq = 0;
    private ContentResolver mContentResolver;
    private Uri mUri;
    private ContentValues mContentValues;
    private Integer sequenceNumber = 0;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);

        /*
         * TODO: Use the TextView to display your messages. Though there is no grading component
         * on how you display the messages, if you implement it, it'll make your debugging easier.
         */
        TextView tv = (TextView) findViewById(R.id.textView1);
        tv.setMovementMethod(new ScrollingMovementMethod());

        /*
         * Registers OnPTestClickListener for "button1" in the layout, which is the "PTest" button.
         * OnPTestClickListener demonstrates how to access a ContentProvider.
         */
        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(tv, getContentResolver()));

        TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        myPort = String.valueOf((Integer.parseInt(portStr) * 2));

        Log.d(TAG, portStr);
        Log.d(TAG, myPort);

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
            Log.e(TAG, "Can't create a ServerSocket");
            return;
        }

        mUri = buildUri("content", "edu.buffalo.cse.cse486586.groupmessenger2.provider");
        mContentResolver = getContentResolver();

        final EditText editText = (EditText) findViewById(R.id.editText1);
        final Button sendButton = (Button) findViewById(R.id.button4);
        map = new HashMap<>();
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                String msg = editText.getText().toString() + "\n";
                editText.setText(""); // This is one way to reset the input box.
                TextView localTextView = (TextView) findViewById(R.id.textView1);
                localTextView.append("->" + msg); // This is one way to display a string.

                sequenceNumber++;
                msg = msg.replace("\n", "");
                String msgWithHeader = sequenceNumber + "." + myPort + SEPERATOR + INITIAL + SEPERATOR + msg;

                /*
                 * Note that the following AsyncTask uses AsyncTask.SERIAL_EXECUTOR, not
                 * AsyncTask.THREAD_POOL_EXECUTOR as the above ServerTask does.
                 */
                for (int i = 0; i < REMOTE_PORT_ARRAY.length; i++) {
                    if (!REMOTE_PORT_ARRAY[i].equalsIgnoreCase(myPort))
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msgWithHeader, REMOTE_PORT_ARRAY[i]);
//                    map.put(msg, new Message(new Double(sequenceNumber + "." + myPort), msg, 0));
                    else {
                        Message m = new Message(Double.parseDouble(sequenceNumber + "." + myPort), msg, 1);
                        map.put(msg, m);
                        deliveryQueue.add(m);
                    }
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }

    private Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
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
                Matcher matcher = null;
                while (true) {
                    Socket client = serverSocket.accept();
                    Log.i(TAG, "Client - " + client.getPort() + " made a request");
                    BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    PrintWriter out =
                            new PrintWriter(client.getOutputStream(), true);
                    String inputLine = null;
                    while ((inputLine = br.readLine()) != null) {
                        Log.i(TAG, "Received the message " + inputLine + " from the buffer. Will act on it now");
                        //received message
                        matcher = p.matcher(inputLine);
                        if (matcher.find()) {
                            String receivedPriorityString = matcher.group(1);
                            String receivedPriorityInt = matcher.group(2);
                            String receivedProcessID = matcher.group(3);
                            String receivedMsgType = matcher.group(4);
                            String receivedMsg = matcher.group(5);
                            Double receivedPriorityDouble = Double.parseDouble(receivedPriorityString);

                            if (receivedMsgType.equals(INITIAL)) {
                                Log.i(TAG, "Got the initial request from " + receivedProcessID + " with msg - " + receivedMsg);
                                String msgToSend = ++sequenceNumber + "." + myPort + SEPERATOR + PROPOSED + SEPERATOR + receivedMsg;

                                Message message = new Message(new Double(sequenceNumber + "." + myPort), receivedMsg, 2);
                                deliveryQueue.add(message);
                                map.put(message.msg, message);

                                Log.d(TAG, "ServerTask.doInBackground() - Sending proposal " + message.priority
                                        + " for msg " + receivedMsg + " to " + receivedProcessID);

                                createSocketAndWrite(receivedProcessID, msgToSend);

                            } else if (receivedMsgType.equals(PROPOSED)) {
                                Log.i(TAG, "Got the proposal from " + receivedProcessID + " for msg - " + receivedMsg);
                                Message proposedMsg = map.get(receivedMsg);

                                if (receivedPriorityDouble > proposedMsg.priority) {
                                    proposedMsg.priority = receivedPriorityDouble;
                                }
                                proposedMsg.receiveCounter++;

                                deliveryQueue.remove(proposedMsg);
                                deliveryQueue.add(proposedMsg);

                                if (proposedMsg.isDeliverable()) {

                                    String msgToSend = proposedMsg.priority + SEPERATOR + FINAL + SEPERATOR + proposedMsg.msg;
                                    for (int i = 0; i < REMOTE_PORT_ARRAY.length; i++) {
                                        if (!REMOTE_PORT_ARRAY[i].equals(myPort)) {
                                            String remotePort = REMOTE_PORT_ARRAY[i];
                                            Log.i(TAG, "Got all proposals. Sending final msg " + proposedMsg.msg + " with priority " + proposedMsg.priority + " to " + remotePort);
                                            createSocketAndWrite(remotePort, msgToSend);
                                        }
                                    }
                                }
                                processQueue();
                            } else if (receivedMsgType.equals(FINAL)) {
                                Log.i(TAG, "Got the final confirmation from " + receivedProcessID + " for msg - " + receivedMsg + " with priority " + receivedPriorityDouble);
                                Message m = map.get(receivedMsg);
                                m.priority = receivedPriorityDouble;
                                m.receiveCounter = 5;

                                deliveryQueue.remove(m);
                                deliveryQueue.add(m);
                                processQueue();
                            }
                        }
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }

        private void createSocketAndWrite(String port, String msg) {
            try (Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                    Integer.parseInt(port));
                 PrintWriter clientOut =
                         new PrintWriter(socket.getOutputStream(), true);) {
                clientOut.println(msg);
            } catch (UnknownHostException e) {
                Log.e(TAG, "ClientTask UnknownHostException");
            } catch (IOException e) {
                Log.e(TAG, "ClientTask socket IOException");
            }
        }

        private void processQueue() {
            while (deliveryQueue.peek() != null && deliveryQueue.peek().isDeliverable()) {
                Message topMessage = deliveryQueue.remove();
                map.remove(topMessage.msg);
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

            mContentValues = new ContentValues();
            mContentValues.put(KEY_FIELD, (contentProviderSeq++).toString());
            mContentValues.put(VALUE_FIELD, strReceived);

            mContentResolver.insert(mUri, mContentValues);

            return;
        }
    }

    /**
     * ClientTask is an AsyncTask that sends a string over the network.
     * It is created by ClientTask.executeOnExecutor() call whenever send button is clicked
     */
    private class ClientTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... msgs) {
            String remotePort = msgs[1];
            String msgToSend = msgs[0].replace("\n", "");

            try (Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                    Integer.parseInt(remotePort));
                 PrintWriter out =
                         new PrintWriter(socket.getOutputStream(), true);) {

                Log.d(TAG, "ClientTask.doInBackground() - Sending msg -" + msgToSend + " to " + remotePort);
                out.println(msgToSend);
            } catch (UnknownHostException e) {
                Log.e(TAG, "ClientTask UnknownHostException");
            } catch (IOException e) {
                Log.e(TAG, "ClientTask socket IOException");
            }

            return null;
        }
    }

    private class Message implements Comparable<Message> {
        Double priority;
        String msg;
        boolean deliverable;
        int receiveCounter = 0;

        private Message(Double priority, String msg) {
            this.priority = priority;
            this.msg = msg;
            this.deliverable = false;
        }

        private Message(Double priority, String msg, int receiveCounter) {
            this.priority = priority;
            this.msg = msg;
            this.receiveCounter = receiveCounter;
        }

        boolean isDeliverable() {
            return (receiveCounter == 5);
        }

        @Override
        public int compareTo(Message another) {
            return this.priority.compareTo(another.priority);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Message))
                return false;
            return this.msg.equals(((Message) other).msg);
        }

        @Override
        public String toString() {
            return "Message{" +
                    "priority=" + priority +
                    ", msg='" + msg + '\'' +
                    ", receivedFromAvd=";
        }
    }

}
