import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;

public class HTTPFileServer {
    private static final int PORT = 8080;
    private static final String SHARED_FOLDER = "shared_files";
    private static final String UPLOAD_FOLDER = "uploads";
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private boolean running = false;

    public HTTPFileServer() {
        createDirectories();
        threadPool = Executors.newFixedThreadPool(10);
    }

    private static class HTTPRequest {
        String method;
        String path;
        String version;
        Map<String, String> headers;
    }

    private void createDirectories() {
        File sharedDir = new File(SHARED_FOLDER);
        File uploadDir = new File(UPLOAD_FOLDER);

        if (!sharedDir.exists()) {
            sharedDir.mkdir();
            System.out.println("Created " + SHARED_FOLDER + " directory. Place files to share inside it.");
        }

        if (!uploadDir.exists()) {
            uploadDir.mkdir();
            System.out.println("Created " + UPLOAD_FOLDER + " directory for uploaded files.");
        }
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(PORT);
        running = true;
        System.out.println("HTTP File Server started on port " + PORT);
        System.out.println("Endpoints:");
        System.out.println("  GET  /download?filename=<filename> - Download a file");
        System.out.println("  POST /upload - Upload a file");

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting client connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (InputStream rawInput = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream()) {

            BufferedReader headerReader = new BufferedReader(new InputStreamReader(rawInput));
            HTTPRequest request = parseHTTPRequest(headerReader);

            if (request == null) {
                sendErrorResponse(out, 400, "Bad Request");
                return;
            }

            System.out.println("Received " + request.method + " " + request.path);

            if (request.path.startsWith("/download")) {
                handleDownloadRequest(request, out);
            } else if (request.path.equals("/upload")) {

                handleUploadRequest(request, rawInput, out);
            } else {
                sendErrorResponse(out, 404, "Not Found");
            }

        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private HTTPRequest parseHTTPRequest(BufferedReader in) throws IOException {
        String requestLine = in.readLine();
        if (requestLine == null)
            return null;

        String[] parts = requestLine.split(" ");
        if (parts.length != 3)
            return null;

        HTTPRequest request = new HTTPRequest();
        request.method = parts[0];
        request.path = parts[1];
        request.version = parts[2];
        request.headers = new HashMap<>();

        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            String[] headerParts = line.split(": ", 2);
            if (headerParts.length == 2) {
                request.headers.put(headerParts[0].toLowerCase(), headerParts[1]);
            }
        }

        return request;
    }

    private void handleDownloadRequest(HTTPRequest request, OutputStream out) throws IOException {
        if (!"GET".equals(request.method)) {
            sendErrorResponse(out, 405, "Method Not Allowed");
            return;
        }

        String filename = extractFilename(request.path);
        if (filename == null) {
            sendErrorResponse(out, 400, "Bad Request: filename parameter required");
            return;
        }

        File fileToSend = new File(SHARED_FOLDER, filename);

        if (!fileToSend.exists() || !fileToSend.isFile()) {
            sendErrorResponse(out, 404, "File Not Found");
            System.out.println("File not found: " + filename);
            return;
        }

        sendFileResponse(out, fileToSend, filename);
        System.out.println("Sent file: " + filename + " (" + fileToSend.length() + " bytes)");
    }

    private void handleUploadRequest(HTTPRequest request, InputStream clientIn, OutputStream out) throws IOException {
        if (!"POST".equals(request.method)) {
            sendErrorResponse(out, 405, "Method Not Allowed");
            return;
        }

        String contentLengthStr = request.headers.get("content-length");
        if (contentLengthStr == null) {
            sendErrorResponse(out, 400, "Bad Request: Content-Length header required");
            return;
        }

        long contentLength;
        try {
            contentLength = Long.parseLong(contentLengthStr);
        } catch (NumberFormatException e) {
            sendErrorResponse(out, 400, "Bad Request: Invalid Content-Length");
            return;
        }

        // Create filename using upload_<timestamp> format as specified
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String filename = "upload_" + timestamp;

        // Try to preserve original extension if available
        String originalFilename = extractOriginalFilename(request.headers);
        if (originalFilename != null && originalFilename.contains(".")) {
            String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            filename += extension;
        }

        File uploadFile = new File(UPLOAD_FOLDER, filename);

        long bytesReceived = saveUploadedFile(clientIn, uploadFile, contentLength);

        String responseBody = "File uploaded successfully: " + filename + " (" + bytesReceived + " bytes)";
        sendTextResponse(out, 200, "OK", responseBody);
        System.out.println("Received file: " + filename + " (" + bytesReceived + " bytes)");
    }

    private String extractFilename(String path) {
        if (!path.contains("?"))
            return null;

        String query = path.substring(path.indexOf("?") + 1);
        String[] params = query.split("&");

        for (String param : params) {
            String[] keyValue = param.split("=", 2);
            if (keyValue.length == 2 && "filename".equals(keyValue[0])) {
                try {
                    return URLDecoder.decode(keyValue[1], "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    return keyValue[1];
                }
            }
        }
        return null;
    }

    private String extractOriginalFilename(Map<String, String> headers) {
        String contentDisposition = headers.get("content-disposition");
        if (contentDisposition != null) {
            String[] parts = contentDisposition.split(";");
            for (String part : parts) {
                part = part.trim();
                if (part.startsWith("filename=")) {
                    String filename = part.substring("filename=".length());
                    if (filename.startsWith("\"") && filename.endsWith("\"")) {
                        filename = filename.substring(1, filename.length() - 1);
                    }
                    return filename;
                }
            }
        }

        String customFilename = headers.get("x-filename");
        if (customFilename != null) {
            return customFilename;
        }

        return null;
    }

    private long saveUploadedFile(InputStream in, File outputFile, long contentLength) throws IOException {
        long bytesReceived = 0;
        byte[] buffer = new byte[8192];

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            while (bytesReceived < contentLength) {
                int bytesToRead = (int) Math.min(buffer.length, contentLength - bytesReceived);
                int bytesRead = in.read(buffer, 0, bytesToRead);
                if (bytesRead == -1)
                    break;

                fos.write(buffer, 0, bytesRead);
                bytesReceived += bytesRead;
            }
        }
        return bytesReceived;
    }

    private void sendFileResponse(OutputStream out, File file, String filename) throws IOException {
        PrintWriter writer = new PrintWriter(out);

        writer.println("HTTP/1.1 200 OK");
        writer.println("Content-Type: application/octet-stream");
        writer.println("Content-Disposition: attachment; filename=\"" + filename + "\"");
        writer.println("Content-Length: " + file.length());
        writer.println();
        writer.flush();

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        out.flush();
    }

    private void sendTextResponse(OutputStream out, int statusCode, String statusText, String body) throws IOException {
        PrintWriter writer = new PrintWriter(out);

        writer.println("HTTP/1.1 " + statusCode + " " + statusText);
        writer.println("Content-Type: text/plain");
        writer.println("Content-Length: " + body.length());
        writer.println();
        writer.print(body);
        writer.flush();
    }

    private void sendErrorResponse(OutputStream out, int statusCode, String message) throws IOException {
        sendTextResponse(out, statusCode, message, message);
    }

    public void stop() throws IOException {
        running = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
        threadPool.shutdown();
    }

    public static void main(String[] args) {
        HTTPFileServer server = new HTTPFileServer();
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }
}