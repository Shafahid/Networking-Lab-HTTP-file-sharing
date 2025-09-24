import java.io.*;
import java.net.*;
import java.util.*;

public class HTTPFileClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8080;

    public static void main(String[] args) {
        HTTPFileClient client = new HTTPFileClient();
        Scanner scanner = new Scanner(System.in);

        System.out.println("Server: " + SERVER_HOST + ":" + SERVER_PORT);
        System.out.println();

        while (true) {
            System.out.println("Choose an option:");
            System.out.println("1. Upload file");
            System.out.println("2. Download file");
            System.out.println("3. Exit");
            System.out.print("Enter your choice (1-3): ");

            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1":
                    client.uploadFile(scanner);
                    break;
                case "2":
                    client.downloadFile(scanner);
                    break;
                case "3":
                    return;
                default:
                    System.out.println("Invalid choice. Please enter 1, 2, or 3.");
            }
            System.out.println();
        }
    }

    private void uploadFile(Scanner scanner) {
        System.out.print("Enter the path of the file to upload: ");
        String filePath = scanner.nextLine().trim();

        File fileToUpload = new File(filePath);
        if (!fileToUpload.exists() || !fileToUpload.isFile()) {
            System.out.println("Error: File not found or is not a valid file.");
            return;
        }

        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream()) {

            System.out.println("Connected to server. Uploading file: " + fileToUpload.getName() +
                    " (" + fileToUpload.length() + " bytes)");

            // Build HTTP request headers
            String filename = fileToUpload.getName();

            StringBuilder headers = new StringBuilder();
            headers.append("POST /upload HTTP/1.1\r\n");
            headers.append("Host: ").append(SERVER_HOST).append(":").append(SERVER_PORT).append("\r\n");
            headers.append("Content-Type: application/octet-stream\r\n");
            headers.append("Content-Length: ").append(fileToUpload.length()).append("\r\n");
            headers.append("X-Filename: ").append(filename).append("\r\n");
            headers.append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\r\n");
            headers.append("Connection: close\r\n");
            headers.append("\r\n"); // Empty line to end headers

            out.write(headers.toString().getBytes());
            out.flush();

            long totalBytesSent = 0;
            try (FileInputStream fis = new FileInputStream(fileToUpload)) {
                byte[] buffer = new byte[8192];
                int bytesRead;

                System.out.print("Uploading: [");
                int progressBarWidth = 20;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytesSent += bytesRead;

                    // Show progress
                    int progress = (int) ((totalBytesSent * progressBarWidth) / fileToUpload.length());
                    System.out.print("\rUploading: [");
                    for (int i = 0; i < progressBarWidth; i++) {
                        if (i < progress) {
                            System.out.print("=");
                        } else if (i == progress) {
                            System.out.print(">");
                        } else {
                            System.out.print(" ");
                        }
                    }
                    System.out.print("] " + (totalBytesSent * 100 / fileToUpload.length()) + "%");
                }
                System.out.println();
            }

            out.flush();

            System.out.println("Waiting for server response...");
            String fullResponse = readFullSocketResponse(in);

            String[] responseLines = fullResponse.split("\n", 2);
            String statusLine = responseLines[0];
            String responseBody = responseLines.length > 1 ? responseLines[1] : "";

            System.out.println("=== SERVER RESPONSE ===");
            System.out.println("Status: " + statusLine);
            if (!responseBody.isEmpty()) {
                System.out.println("Message: " + responseBody);
            }
            System.out.println("=======================");

            if (statusLine.contains("200 OK")) {
                System.out.println("Upload successful!");
            } else {
                System.out.println("Upload failed!");
            }

        } catch (IOException e) {
            System.out.println("Error uploading file: " + e.getMessage());
        }
    }

    private void downloadFile(Scanner scanner) {
        System.out.print("Enter the filename to download: ");
        String filename = scanner.nextLine().trim();

        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream()) {

            System.out.println("Connected to server. Requesting file: " + filename);

            String request = "GET /download?filename=" + URLEncoder.encode(filename, "UTF-8") +
                    " HTTP/1.1\r\n" +
                    "Host: " + SERVER_HOST + ":" + SERVER_PORT + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";

            out.write(request.getBytes());
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            // Read and display status line
            String statusLine = reader.readLine();
            if (statusLine == null) {
                System.out.println("No response from server");
                return;
            }

            System.out.println("=== SERVER RESPONSE ===");
            System.out.println("Status: " + statusLine);

            if (!statusLine.contains("200 OK")) {

                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:"))
                        continue;
                    errorResponse.append(line).append("\n");
                }
                System.out.println("Error details: " + errorResponse.toString().trim());
                System.out.println("=======================");
                System.out.println("Download failed!");
                return;
            }

            System.out.println("File found, starting download...");

            long contentLength = -1;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Long.parseLong(line.substring(15).trim());
                        System.out.println("File size: " + contentLength + " bytes");
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                }
            }

            System.out.println("=======================");

            File downloadDir = new File("downloads");
            if (!downloadDir.exists()) {
                downloadDir.mkdir();
            }

            File downloadFile = new File(downloadDir, "downloaded_" + filename);

            long totalBytesReceived = 0;
            try (FileOutputStream fos = new FileOutputStream(downloadFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;

                System.out.print("Downloading: [");
                int progressBarWidth = 20;

                while ((bytesRead = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesReceived += bytesRead;

                    if (contentLength > 0) {
                        int progress = (int) ((totalBytesReceived * progressBarWidth) / contentLength);
                        System.out.print("\rDownloading: [");
                        for (int i = 0; i < progressBarWidth; i++) {
                            if (i < progress) {
                                System.out.print("=");
                            } else if (i == progress) {
                                System.out.print(">");
                            } else {
                                System.out.print(" ");
                            }
                        }
                        System.out.print("] " + (totalBytesReceived * 100 / contentLength) + "%");
                    }
                }
                System.out.println();
            }

            System.out.println("Download complete!");
            System.out.println("File saved as: " + downloadFile.getName());
            System.out.println("Total bytes received: " + totalBytesReceived);

        } catch (IOException e) {
            System.out.println("Error downloading file: " + e.getMessage());
        }
    }

    private String readFullSocketResponse(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder response = new StringBuilder();
        String line;

        if ((line = reader.readLine()) != null) {
            response.append(line);
        }

        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            response.append("\n").append(line);
        }

        if (reader.ready()) {
            while ((line = reader.readLine()) != null) {
                response.append("\n").append(line);
            }
        }

        return response.toString().trim();
    }
}