package com.quietpanel.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The server is reachable only through the local port created by ADB forward.
 */
public final class TransportServer {
    private static final int PORT = 27183;

    public interface Listener {
        void onConnectionChanged(boolean connected, String detail);
        void onStateReceived(JSONObject system, JSONArray disks);
        void onActionResult(long id, boolean ok, String message);
        void onDisplayStateChanged(boolean displayOn);
        void onPageConfigReceived(JSONArray enabledPages);
    }

    private final Listener listener;
    private final AtomicLong nextActionId = new AtomicLong(1);
    private volatile boolean running;

    private Thread serverThread;
    private ServerSocket serverSocket;
    private Socket activeClientSocket;
    private BufferedWriter writer;

    public TransportServer(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runIpServer();
            }
        }, "quietpanel-adb-server");
        serverThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        closeQuietly(activeClientSocket);
        activeClientSocket = null;
        writer = null;
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
    }

    public long sendAction(String action) {
        long id = nextActionId.getAndIncrement();
        try {
            JSONObject message = new JSONObject();
            message.put("v", 1);
            message.put("type", "action");
            message.put("id", id);
            message.put("action", action);
            if (!writeMessage(message)) {
                listener.onActionResult(id, false, "未連線至電腦");
            }
        } catch (Exception error) {
            listener.onActionResult(id, false, safeMessage(error));
        }
        return id;
    }

    private void runIpServer() {
        while (running) {
            try {
                synchronized (this) {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));
                }
                notifyConnection(false, waitingMessage());

                while (running) {
                    Socket socket = serverSocket.accept();
                    if (!running) {
                        closeQuietly(socket);
                        break;
                    }

                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(0);

                    synchronized (this) {
                        if (activeClientSocket != null) {
                            closeQuietly(socket);
                            continue;
                        }
                        activeClientSocket = socket;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    BufferedWriter newWriter = new BufferedWriter(
                            new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
                    synchronized (this) {
                        writer = newWriter;
                    }

                    handleStreamSession(reader, socket, "ADB 連線");
                }
            } catch (Exception error) {
                if (running) {
                    notifyConnection(false, "ADB 服務異常：" + safeMessage(error));
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
            } finally {
                synchronized (this) {
                    closeQuietly(serverSocket);
                    serverSocket = null;
                }
            }
        }
    }

    private String waitingMessage() {
        return "等待 USB ADB 連線…";
    }

    private void handleStreamSession(BufferedReader reader, Socket clientSocket,
                                     String transportName) {
        notifyConnection(true, transportName + " 已建立");
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                handleMessage(line);
            }
        } catch (Exception error) {
            if (running) {
                notifyConnection(false, transportName + " 中斷：" + safeMessage(error));
            }
        } finally {
            synchronized (this) {
                if (activeClientSocket == clientSocket) {
                    writer = null;
                    activeClientSocket = null;
                }
            }
            closeQuietly(clientSocket);
            if (running) {
                notifyConnection(false, waitingMessage());
            }
        }
    }

    private void handleMessage(String line) {
        try {
            JSONObject message = new JSONObject(line);
            if (message.optInt("v", 0) != 1) {
                return;
            }

            String type = message.optString("type", "");
            if ("hello".equals(type)) {
                String version = message.optString("version", "?");
                notifyConnection(true, "Rust Bridge " + version + " 已連線");
                JSONObject acknowledgement = new JSONObject();
                acknowledgement.put("v", 1);
                acknowledgement.put("type", "hello_ack");
                acknowledgement.put("version", BuildConfig.VERSION_NAME);
                writeMessage(acknowledgement);
            } else if ("display_state".equals(type)) {
                listener.onDisplayStateChanged(message.optBoolean("on", true));
            } else if ("page_config".equals(type)) {
                listener.onPageConfigReceived(message.optJSONArray("enabled"));
            } else if ("state".equals(type)) {
                listener.onStateReceived(
                        message.optJSONObject("system"),
                        message.optJSONArray("disks"));
            } else if ("action_result".equals(type)) {
                listener.onActionResult(
                        message.optLong("id", -1),
                        message.optBoolean("ok", false),
                        message.optString("message", ""));
            } else if ("ping".equals(type)) {
                JSONObject pong = new JSONObject();
                pong.put("v", 1);
                pong.put("type", "pong");
                writeMessage(pong);
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized boolean writeMessage(JSONObject message) {
        if (!running || writer == null || activeClientSocket == null) {
            return false;
        }

        try {
            writer.write(message.toString());
            writer.newLine();
            writer.flush();
            return true;
        } catch (Exception error) {
            closeQuietly(activeClientSocket);
            writer = null;
            activeClientSocket = null;
            return false;
        }
    }

    private void notifyConnection(boolean connected, String detail) {
        if (listener != null) {
            listener.onConnectionChanged(connected, detail);
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName()
                : message;
    }

    private static void closeQuietly(Object socket) {
        if (socket == null) {
            return;
        }
        try {
            if (socket instanceof Socket) {
                ((Socket) socket).close();
            } else if (socket instanceof ServerSocket) {
                ((ServerSocket) socket).close();
            }
        } catch (Exception ignored) {
        }
    }
}
