package com.quietpanel.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

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
    private Socket clientSocket;
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
                runServer();
            }
        }, "quietpanel-server");
        serverThread.start();
    }

    public long sendAction(String action) {
        long id = nextActionId.getAndIncrement();
        try {
            JSONObject message = new JSONObject();
            message.put("v", 1);
            message.put("type", "action");
            message.put("id", id);
            message.put("action", action);
            return writeMessage(message) ? id : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    public void stop() {
        Thread thread;
        synchronized (this) {
            running = false;
            closeQuietly(clientSocket);
            closeQuietly(serverSocket);
            clientSocket = null;
            serverSocket = null;
            writer = null;
            thread = serverThread;
            serverThread = null;
        }

        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runServer() {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), PORT), 1);

            synchronized (this) {
                if (!running) {
                    closeQuietly(server);
                    return;
                }
                serverSocket = server;
            }

            notifyConnection(false, "等待電腦連線");

            while (running) {
                Socket socket = server.accept();
                if (!running) {
                    closeQuietly(socket);
                    break;
                }
                serveClient(socket);
            }
        } catch (Exception error) {
            if (running) {
                notifyConnection(false, "連線服務錯誤：" + safeMessage(error));
            }
        } finally {
            synchronized (this) {
                closeQuietly(serverSocket);
                serverSocket = null;
            }
        }
    }

    private void serveClient(Socket socket) {
        try {
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            BufferedWriter clientWriter = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

            synchronized (this) {
                clientSocket = socket;
                writer = clientWriter;
            }

            notifyConnection(true, "電腦已連線");

            String line;
            while (running && (line = reader.readLine()) != null) {
                handleMessage(line);
            }
        } catch (Exception error) {
            if (running) {
                notifyConnection(false, "連線中斷：" + safeMessage(error));
            }
        } finally {
            synchronized (this) {
                if (clientSocket == socket) {
                    writer = null;
                    clientSocket = null;
                }
            }
            closeQuietly(socket);
            if (running) {
                notifyConnection(false, "等待電腦重新連線");
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
                acknowledgement.put("version", "6.5.0");
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
        if (!running || writer == null || clientSocket == null) {
            return false;
        }

        try {
            writer.write(message.toString());
            writer.newLine();
            writer.flush();
            return true;
        } catch (Exception error) {
            closeQuietly(clientSocket);
            writer = null;
            clientSocket = null;
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

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
