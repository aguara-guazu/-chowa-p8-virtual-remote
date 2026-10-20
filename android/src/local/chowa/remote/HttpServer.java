package local.chowa.remote;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Blocking HTTP/1.1 server (one request per connection) exposing the web UI and
 * the JSON API consumed by {@code assets/index.html}.
 */
final class HttpServer {

    private static final String TAG = "ChowaRemote";
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 20000;

    private final int port;
    private final byte[] indexHtml;
    private final Projector projector;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private volatile ServerSocket serverSocket;

    HttpServer(int port, byte[] indexHtml, Projector projector) {
        this.port = port;
        this.indexHtml = indexHtml;
        this.projector = projector;
    }

    void start() {
        Thread acceptor = new Thread(this::acceptLoop, "http-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void stop() {
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            serverSocket = server;
            Log.i(TAG, "listening on port " + port);
            while (!server.isClosed()) {
                Socket client = server.accept();
                pool.execute(() -> handle(client));
            }
        } catch (SocketException e) {
            Log.i(TAG, "server socket closed");
        } catch (IOException e) {
            Log.e(TAG, "server failure", e);
        }
    }

    private static final class Request {
        String method;
        String path;
        String query = "";
        byte[] body = new byte[0];
    }

    private static final class Response {
        final int status;
        final String contentType;
        final byte[] body;

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Response json(int status, JSONObject payload) {
            return new Response(status, "application/json", payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        static Response error(int status, String message) {
            try {
                return json(status, new JSONObject().put("error", message));
            } catch (JSONException e) {
                return new Response(status, "text/plain", message.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            Request request = parse(in);
            Response response = request == null
                    ? Response.error(400, "malformed request")
                    : route(request);
            write(socket.getOutputStream(), response);
        } catch (IOException e) {
            Log.w(TAG, "connection error: " + e.getMessage());
        }
    }

    private static Request parse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int tail = 0;
        while (tail != 0x0d0a0d0a) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            if (head.size() > MAX_HEADER_BYTES) {
                return null;
            }
            tail = (tail << 8) | b;
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            return null;
        }
        Request request = new Request();
        request.method = requestLine[0];
        String target = requestLine[1];
        int q = target.indexOf('?');
        request.path = q >= 0 ? target.substring(0, q) : target;
        request.query = q >= 0 ? target.substring(q + 1) : "";

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        int length = 0;
        if (headers.containsKey("content-length")) {
            try {
                length = Integer.parseInt(headers.get("content-length"));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (length < 0 || length > MAX_BODY_BYTES) {
            return null;
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                return null;
            }
            read += n;
        }
        request.body = body;
        return request;
    }

    private Response route(Request req) {
        try {
            if ("GET".equals(req.method)) {
                switch (req.path) {
                    case "/":
                    case "/index.html":
                        return new Response(200, "text/html; charset=utf-8", indexHtml);
                    case "/api/status":
                        return Response.json(200, projector.status());
                    case "/api/apps":
                        return Response.json(200, new JSONObject().put("apps",
                                Projector.toArray(projector.apps(req.query.contains("refresh")))));
                    default:
                        return Response.error(404, "not found");
                }
            }
            if (!"POST".equals(req.method)) {
                return Response.error(405, "method not allowed");
            }
            JSONObject data = req.body.length == 0
                    ? new JSONObject()
                    : new JSONObject(new String(req.body, StandardCharsets.UTF_8));
            switch (req.path) {
                case "/api/key": {
                    Integer code = Projector.SCANCODES.get(data.getString("key"));
                    if (code == null) {
                        throw new IllegalArgumentException("unknown key: " + data.getString("key"));
                    }
                    projector.hold(code, data.optString("action", "tap"));
                    break;
                }
                case "/api/focus":
                    projector.focus(data.getString("direction"), data.optString("action", "tap"));
                    break;
                case "/api/volume":
                    projector.setVolume(data.getInt("level"));
                    break;
                case "/api/text":
                    projector.sendText(data.optString("text", ""));
                    break;
                case "/api/textkey":
                    projector.textKey(data.getString("key"));
                    break;
                case "/api/open":
                    projector.openScreen(data.getString("screen"));
                    break;
                case "/api/launch":
                    projector.launch(data.getString("component"));
                    break;
                default:
                    return Response.error(404, "not found");
            }
            return Response.json(200, new JSONObject().put("ok", true));
        } catch (JSONException | IllegalArgumentException e) {
            return Response.error(400, "bad request: " + e.getMessage());
        } catch (IOException e) {
            return Response.error(502, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static void write(OutputStream out, Response response) throws IOException {
        String reason;
        switch (response.status) {
            case 200: reason = "OK"; break;
            case 400: reason = "Bad Request"; break;
            case 404: reason = "Not Found"; break;
            case 405: reason = "Method Not Allowed"; break;
            default: reason = "Bad Gateway"; break;
        }
        String head = "HTTP/1.1 " + response.status + " " + reason + "\r\n"
                + "Content-Type: " + response.contentType + "\r\n"
                + "Content-Length: " + response.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(response.body);
        out.flush();
    }
}
