package com.app1;

import com.dynatrace.adk.DynaTraceADKFactory;
import com.dynatrace.adk.Tagging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.jms.Queue;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

@RestController
public class App1Controller {

    private static final Logger log = LoggerFactory.getLogger(App1Controller.class);

    public static final String MESSAGE_URL = "http://localhost:8988/message";

    @Autowired
    private JmsTemplate jms;

    @Autowired
    private Queue chatQueue;

    @Autowired
    private RestTemplate http;

    @Autowired
    @Qualifier("threadPool")
    private ExecutorService threadPool;

    @RequestMapping("/send")
    public MessageDTO[] send(@RequestParam(value = "message") String message) {
        log.info("HTTP POST {} [{}]", MESSAGE_URL, message);
        http.postForLocation(MESSAGE_URL, new MessageDTO(message));

        log.info("HTTP GET {}", MESSAGE_URL);
        return http.getForEntity(MESSAGE_URL, MessageDTO[].class).getBody();
    }

    @RequestMapping("/sendJMS")
    public MessageDTO sendJMS(@RequestParam(value = "message") String message) {
        jms.send(chatQueue, session -> session.createTextMessage(message));
        log.info("JMS SENT [{}] to {}", message, chatQueue);
        return new MessageDTO("JMS message has been sent to Active MQ");
    }

    @RequestMapping(value = "/sendTcp")
    public String sendTcp(@RequestParam(value = "message") String message) throws IOException {
        try (Socket socket = new Socket("localhost", 8985)) {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            writer.println(message);
            writer.flush();
            log.info("TCP SENT " + socket.getRemoteSocketAddress() + " [" + message + "]");

            String result = reader.readLine();
            log.info("TCP RECEIVED " + socket.getRemoteSocketAddress() + " [" + result + "]");

            return result;
        }
    }

    @RequestMapping("/sendTcpTagging")
    public String sendTcpTagging(@RequestParam(value = "message") String message) throws IOException {
        Tagging tagging = DynaTraceADKFactory.createTagging();
        String requestTag = tagging.getTagAsString();
        tagging.linkClientPurePath(false, requestTag);
        return sendTcp(message + "|" + requestTag);
    }

    @RequestMapping("/sendAsNewThread")
    public MessageDTO[] sendAsNewThread(@RequestParam(value = "message") String message) throws InterruptedException {
        AtomicReference<MessageDTO[]> result = new AtomicReference<>();
        // Dynatrace does not associate new thread with current pure path
        Thread newThread = new Thread(() -> {
            log.info("in new thread");
            result.set(send(message));
        });

        newThread.start();
        newThread.join();
        return result.get();
    }

    @RequestMapping("/sendToThreadPool")
    public MessageDTO[] sendToThreadPool(@RequestParam(value = "message") String message) throws Exception {
        Future<MessageDTO[]> future = threadPool.submit(() -> {
            log.info("in thread pool");
            return send(message);
        });
        return future.get();
    }

    @RequestMapping("/sendToThreadPoolAsync")
    public MessageDTO sendToThreadPoolAsync(@RequestParam(value = "message") String message) throws Exception {
        threadPool.submit(() -> {
            log.info("in thread pool");
            delay(1500);
            send(message);
        });

        return new MessageDTO("HTTP call being executed asynchronously");
    }

    @RequestMapping("/sendAsCompletableFuture")
    public MessageDTO[] sendAsCompletableFuture(@RequestParam(value = "message") String message) throws Exception {
        return CompletableFuture
            .supplyAsync(() -> {
                log.info("in completable future HTTP POST {} [{}]", MESSAGE_URL, message);
                return http.postForLocation(MESSAGE_URL, new MessageDTO(message));
            })
            .thenApply((uri) -> {
                log.info("in completable future HTTP GET {}", MESSAGE_URL);
                return http.getForEntity(MESSAGE_URL, MessageDTO[].class).getBody();
            }).get();
    }

    @PostConstruct
    public void init() {
        DynaTraceADKFactory.initialize();
    }

    @PreDestroy
    public void preDestroy() {
        DynaTraceADKFactory.uninitialize();
    }

    private static void delay(long mills) {
        try {
            Thread.sleep(mills);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}