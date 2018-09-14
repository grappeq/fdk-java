package com.fnproject.fn.runtime;


import com.fnproject.fn.api.Headers;
import com.fnproject.fn.api.InputEvent;
import com.fnproject.fn.api.OutputEvent;
import com.fnproject.fn.api.exception.FunctionInputHandlingException;
import com.fnproject.fn.api.exception.FunctionOutputHandlingException;
import com.fnproject.fn.runtime.exception.FunctionIOException;
import com.fnproject.fn.runtime.exception.FunctionInitializationException;
import com.fnproject.fn.runtime.ntv.UnixServerSocket;
import com.fnproject.fn.runtime.ntv.UnixSocket;
import org.apache.http.*;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultBHttpServerConnection;
import org.apache.http.impl.io.EmptyInputStream;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.UriHttpRequestHandlerMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fn HTTP Stream over Unix domain sockets  codec
 * <p>
 * <p>
 * This creates a new unix socket on the address specified by env["FN_LISTENER"] - and accepts requests.
 * <p>
 * This currently only handles exactly one concurrent connection
 * <p>
 * Created on 24/08/2018.
 * <p>
 * (c) 2018 Oracle Corporation
 */
public final class HTTPStreamCodec implements EventCodec, Closeable {
    static {
        System.setProperty("jnr.ffi.asm.enabled", "false");
    }

    private static final String FN_LISTENER = "FN_LISTENER";
    private final Map<String, String> env;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final File socketFile;
    private static final Set<String> stripInputHeaders;
    private static final Set<String> stripOutputHeaders;
    private static final CompletableFuture<Boolean> stopped = new CompletableFuture<>();
    private final UnixServerSocket socket;

    static {
        Set<String> hin = new HashSet<>();
        hin.add("Content-Length");
        hin.add("Host");
        hin.add("Accept-Encoding");
        hin.add("Transfer-Encoding");
        hin.add("User-Agent");
        hin.add("Connection");
        hin.add("TE");

        stripInputHeaders = Collections.unmodifiableSet(hin);

        Set<String> hout = new HashSet<>();
        hout.add("Content-Length");
        hout.add("Transfer-Encoding");
        hout.add("Connection");
        stripOutputHeaders = Collections.unmodifiableSet(hout);
    }


    private String randomString() {
        int leftLimit = 97;
        int rightLimit = 122;
        int targetStringLength = 10;
        Random random = new Random();
        StringBuilder buffer = new StringBuilder(targetStringLength);
        for (int i = 0; i < targetStringLength; i++) {
            int randomLimitedInt = leftLimit + (int)
              (random.nextFloat() * (rightLimit - leftLimit + 1));
            buffer.append((char) randomLimitedInt);
        }
        return buffer.toString();
    }

    /**
     * Construct a new HTTPStreamCodec based on the environment
     *
     * @param env an env map
     */
    public HTTPStreamCodec(Map<String, String> env) {
        this.env = Objects.requireNonNull(env, "env");
        String listenerAddress = getRequiredEnv("FN_LISTENER");

        if (!listenerAddress.startsWith("unix:/")) {
            throw new FunctionInitializationException("Invalid listener address - it should start with unix:/" + listenerAddress);
        }
        String listenerFile = listenerAddress.substring("unix:".length());

        socketFile = new File(listenerFile);


        UnixServerSocket serverSocket = null;
        File listenerDir = socketFile.getParentFile();
        File tempFile = new File(listenerDir, randomString() + ".sock");
        try {

            serverSocket = UnixServerSocket.listen(tempFile.getAbsolutePath(), 1);
            // Adjust socket permissions and move file
            Files.setPosixFilePermissions(tempFile.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"));
            Files.move(tempFile.toPath(), socketFile.toPath());

            this.socket = serverSocket;
        } catch (IOException e) {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }

            }
            throw new FunctionInitializationException("Unable to bind to unix socket in " + socketFile, e);
        }


    }

    @Override
    public void runCodec(Handler h) {

        UriHttpRequestHandlerMapper mapper = new UriHttpRequestHandlerMapper();
        mapper.register("/call", ((request, response, context) -> {
            InputEvent evt;
            try {
                evt = readEvent(request);
            } catch (FunctionInputHandlingException e) {
                response.setStatusCode(500);
                response.setEntity(new StringEntity("{\"message\":\"Invalid input from function\"}", ContentType.APPLICATION_JSON));
                return;
            }

            OutputEvent outEvt;

            try {
                outEvt = h.handle(evt);
            } catch (Exception e) {
                response.setStatusCode(500);
                response.setEntity(new StringEntity("{\"message\":\"Unhandled internal error in FDK\"}", ContentType.APPLICATION_JSON));
                return;
            }

            try {
                writeEvent(outEvt, response);
            } catch (Exception e) {
                // TODO strange edge cases might appear with headers where the response is half written here
                response.setStatusCode(500);
                response.setEntity(new StringEntity("{\"message\":\"Unhandled internal error while generating response FDK\"}", ContentType.APPLICATION_JSON));
            }
        }
        ));

        ImmutableHttpProcessor requestProcess = new ImmutableHttpProcessor(new HttpRequestInterceptor[0], new HttpResponseInterceptor[0]);
        HttpService svc = new HttpService(requestProcess, mapper);

        try {

            while (!stopping.get()) {
                UnixSocket sock;
                try {
                    sock = socket.accept(100);
                    if (sock == null) {
                        continue;
                    }
                    // TODO tweak these properly
                    sock.setSendBufferSize(65535);
                    sock.setReceiveBufferSize(65535);

                } catch (IOException e) {
                    if (stopping.get()) {
                        // ignore IO errors on stop
                        return;
                    }
                    throw new FunctionIOException("failed to accept connection from platform, terminating", e);
                }
                try {
                    DefaultBHttpServerConnection con = new DefaultBHttpServerConnection(65535);
                    con.bind(sock);
                    while (!sock.isClosed()) {
                        try {
                            svc.handleRequest(con, new BasicHttpContext());
                        } catch (Exception ignored) {
                            sock.close();
                        }
                    }
                } catch (IOException ignored) {
                    // TODO should log here?
                } finally {
                    try {
                        sock.close();
                    } catch (IOException ignored) {
                    }
                }

            }
        } finally {
            stopped.complete(true);
        }


    }


    private String getRequiredEnv(String name) {
        String val = env.get(name);
        if (val == null) {
            throw new FunctionInputHandlingException("Required environment variable " + name + " is not set - are you running a function outside of fn run?");
        }
        return val;
    }

    private static String getRequiredHeader(HttpRequest request, String headerName) {
        Header header = request.getFirstHeader(headerName);
        if (header == null) {
            throw new FunctionInputHandlingException("Invalid call, No header \"" + headerName + "\" in request");
        }
        return header.getValue();
    }

    private InputEvent readEvent(HttpRequest request) {

        InputStream bodyStream;
        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest entityEnclosingRequest = (HttpEntityEnclosingRequest) request;
            try {
                bodyStream = entityEnclosingRequest.getEntity().getContent();
            } catch (IOException exception) {
                throw new FunctionInputHandlingException("error handling input", exception);
            }
        } else {
            bodyStream = EmptyInputStream.INSTANCE;
        }


        String deadline = getRequiredHeader(request, "Fn-Deadline");
        String callID = getRequiredHeader(request, "Fn-Call-Id");


        Instant deadlineDate;
        try {
            deadlineDate = Instant.parse(deadline);
        } catch (DateTimeParseException e) {
            throw new FunctionInputHandlingException("Invalid deadline date format", e);
        }

        Headers headersIn = Headers.emptyHeaders();


        for (Header h : request.getAllHeaders()) {
            if (stripInputHeaders.contains(Headers.canonicalKey(h.getName()))) {
                continue;
            }
            headersIn = headersIn.addHeader(h.getName(), h.getValue());
        }

        return new ReadOnceInputEvent(bodyStream, headersIn, callID, deadlineDate);

    }

    private void writeEvent(OutputEvent evt, HttpResponse response) {

        evt.getHeaders().asMap()
          .entrySet()
          .stream()
          .filter(e -> !stripOutputHeaders.contains(e.getKey()))
          .flatMap(e -> e.getValue().stream().map((v) -> new BasicHeader(e.getKey(), v)))
          .forEachOrdered(response::addHeader);

        ContentType contentType = evt.getContentType().map(c -> {
            try {
                return ContentType.parse(c);
            } catch (ParseException e) {
                return ContentType.DEFAULT_BINARY;
            }
        }).orElse(ContentType.DEFAULT_BINARY);

        response.setHeader("Content-Type", contentType.toString());
        response.setStatusLine(new BasicStatusLine(HttpVersion.HTTP_1_1, evt.getStatus().getCode(), evt.getStatus().name()));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // TODO remove output buffering here - possibly change OutputEvent contract to support providing an InputStream?
        try {
            evt.writeToOutput(bos);
        } catch (IOException e) {
            throw new FunctionOutputHandlingException("Error writing output", e);
        }
        byte[] data = bos.toByteArray();
        response.setEntity(new ByteArrayEntity(data, contentType));

    }


    @Override
    public void close() throws IOException {
        if (stopping.compareAndSet(false, true)) {
            socket.close();

            try {
                stopped.get();
            } catch (Exception ignored) {
            }
            socketFile.delete();
        }

    }
}