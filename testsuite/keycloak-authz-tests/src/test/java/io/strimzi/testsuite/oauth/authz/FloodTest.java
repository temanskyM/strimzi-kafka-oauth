/*
 * Copyright 2017-2021, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.testsuite.oauth.authz;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthorizationException;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.strimzi.kafka.oauth.common.OAuthAuthenticator.loginWithClientSecret;

public class FloodTest extends Common {

    private static final Logger log = LoggerFactory.getLogger(FloodTest.class);

    private static ArrayList<Thread> threads = new ArrayList<>();

    private static AtomicInteger startedCount;

    static int sendLimit = 1;


    FloodTest(String kafkaBootstrap, boolean oauthOverPlain) {
        super(kafkaBootstrap, oauthOverPlain);
    }

    public void doTest() throws IOException {
        clientCredentialsWithFloodTest();
    }


    /**
     * This test uses the Kafka listener configured with both OAUTHBEARER and PLAIN.
     *
     * It connects concurrently with multiple producers with different client IDs using the PLAIN mechanism, testing the OAuth over PLAIN functionality.
     * With KeycloakRBACAuthorizer configured, any mixup of the credentials between different clients will be caught as
     * AuthorizationException would be thrown trying to write to the topic if the user context was mismatched.
     *
     * @throws Exception
     */
    void clientCredentialsWithFloodTest() throws IOException {

        String producerPrefix = "kafka-producer-client-";
        String consumerPrefix = "kafka-consumer-client-";

        // 10 parallel producers and consumers
        final int clientCount = 10;

        if (!usePlain) {
            HashMap<String, String> tokens = new HashMap<>();
            for (int i = 1; i <= clientCount; i++) {
                obtainAndStoreToken(producerPrefix, tokens, i);
                obtainAndStoreToken(consumerPrefix, tokens, i);
            }
            this.tokens = tokens;
        }

        // Try write to the mismatched topic - we should get AuthorizationException
        try {
            sendSingleMessage("kafka-producer-client-1", "kafka-producer-client-1-secret", "messages-2");

            Assert.fail("Sending to 'messages-2' using 'kafka-producer-client-1' should fail with AuthorizationException");
        } catch (InterruptedException e) {
            throw new InterruptedIOException("Interrupted");
        } catch (ExecutionException e) {
            Assert.assertTrue("Exception type should be AuthorizationException", e.getCause() instanceof AuthorizationException);
        }

        // Do 20 iterations - each time hitting the broker with 10 parallel requests
        for (int run = 0; run < 20; run++) {

            for (int i = 1; i <= clientCount; i++) {
                String topic = "messages-" + i;

                addProducerThread(producerPrefix + i, producerPrefix + i + "-secret", topic);
                addConsumerThread(consumerPrefix + i, consumerPrefix + i + "-secret", topic, groupForConsumer(i));
            }

            // Start all threads
            startThreads();

            // Wait for all threads to finish
            joinThreads();

            // Check for errors
            checkExceptions();

            // Prepare for the next run
            clearThreads();
        }

        System.out.println();
        System.out.println("   === Test flooding a single topic using kafka-producer-client-1 and kafka-consumer-client-1");
        System.out.println();

        // Now try the same with a single topic
        for (int run = 0; run < 20; run++) {

            for (int i = 1; i <= clientCount; i++) {
                String topic = "messages-1";

                addProducerThread(producerPrefix + "1", producerPrefix + "1" + "-secret", topic);
                addConsumerThread(consumerPrefix + "1", consumerPrefix + "1" + "-secret", topic, groupForConsumer(1));
            }

            // Start all threads
            startThreads();

            // Wait for all threads to finish
            joinThreads();

            // Check for errors
            checkExceptions();

            // Prepare for the next run
            clearThreads();
        }
    }

    private void sendSingleMessage(String clientId, String secret, String topic) throws ExecutionException, InterruptedException {
        Properties props = buildProducerConfig(kafkaBootstrap, usePlain, clientId, secret);
        KafkaProducer<String, String> producer = new KafkaProducer<>(props);

        producer.send(new ProducerRecord<>(topic, "Message 0"))
                .get();
    }

    private String groupForConsumer(int index) {
        return "g" + (index < 10 ? index : 0);
    }

    private void obtainAndStoreToken(String producerPrefix, HashMap<String, String> tokens, int i) throws IOException {
        String clientId = producerPrefix + i;
        String secret = clientId + "-secret";

        tokens.put(clientId, loginWithClientSecret(URI.create(TOKEN_ENDPOINT_URI), null, null,
                clientId, secret, true, null, null).token());
    }


    public static void clearThreads() {
        threads.clear();
    }

    public static void startThreads() {
        startedCount = new AtomicInteger(0);
        for (Thread t : threads) {
            t.start();
        }
    }

    public static void joinThreads() {
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted - exiting ...");
            }
        }
    }

    public static void checkExceptions() {
        try {
            for (Thread t : threads) {
                ((ClientJob) t).checkException();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Test failed due to: ", t);
        }
    }

    public void addProducerThread(String clientId, String secret, String topic) {
        FloodProducer p = new FloodProducer(clientId, secret, topic);
        p.initProducer();
    }

    public void addConsumerThread(String clientId, String secret, String topic, String group) {
        FloodConsumer c = new FloodConsumer(clientId, secret, topic, group);
        c.initConsumer();
    }


    class ClientJob extends Thread {

        final String clientId;
        final String secret;
        final String topic;
        Throwable error;

        ClientJob(String clientId, String secret, String topic) {
            this.clientId = clientId;
            this.secret = secret;
            this.topic = topic;
        }

        void checkException() throws Throwable {
            if (error != null) {
                log.error("Client job error: ", error);
                throw error;
            }
        }
    }

    class FloodConsumer extends ClientJob {

        Consumer<String, String> consumer;

        String group;

        FloodConsumer(String clientId, String secret, String topic, String group) {
            super(clientId, secret, topic);
            this.group = group;
        }

        private void initConsumer() {
            Properties props = buildConsumerConfig(kafkaBootstrap, usePlain, clientId, secret);
            props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, group);
            consumer = new KafkaConsumer<>(props);
            setName("FloodConsumer Runner Thread - " + clientId + " - " + threads.size());
            threads.add(this);
        }

        public void run() {
            int started = startedCount.addAndGet(1);

            try {
                while (started < threads.size()) {
                    Thread.sleep(10);
                    started = startedCount.get();
                }

                for (int i = 0; i < sendLimit; i++) {

                    // This loop ensures some time for topic to be autocreated by producer which has the permissions to create the topic
                    // Whereas the consumer does not have a permission to create a topic.
                    for (int triesLeft = 300; triesLeft > 0; triesLeft--) {
                        try {
                            consume(consumer, topic);
                            System.out.println("[" + clientId + "] Consumed message from '" + topic + "': Message " + i);
                            break;
                        } catch (Throwable t) {
                            if (triesLeft <= 1) {
                                throw t;
                            }
                            Thread.sleep(100);
                        }
                    }

                    if (i < sendLimit - 1) {
                        Thread.sleep(2000);
                    }
                }
            } catch (InterruptedException e) {
                error = new RuntimeException("Interrupted while consuming!");
            } catch (Throwable t) {
                error = t;
            } finally {
                if (consumer != null) {
                    consumer.close();
                }
            }
        }
    }

    class FloodProducer extends ClientJob {

        Producer<String, String> producer;

        FloodProducer(String clientId, String secret, String topic) {
            super(clientId, secret, topic);
        }

        private void initProducer() {
            Properties props = buildProducerConfig(kafkaBootstrap, usePlain, clientId, secret);
            producer = new KafkaProducer<>(props);
            setName("FloodProducer Runner Thread - " + clientId + " - " + threads.size());
            threads.add(this);
        }

        public void run() {
            int started = startedCount.addAndGet(1);

            try {

                while (started < threads.size()) {
                    Thread.sleep(10);
                    started = startedCount.get();
                }

                for (int i = 0; i < sendLimit; i++) {
                    producer.send(new ProducerRecord<>(topic, "Message " + i))
                            .get();

                    System.out.println("[" + clientId + "] Produced message to '" + topic + "': Message " + i);

                    if (i < sendLimit - 1) {
                        Thread.sleep(2000);
                    }
                }
            } catch (InterruptedException e) {
                error = new RuntimeException("Interrupted while sending!");
            } catch (ExecutionException e) {
                error = new RuntimeException("Failed to send message: ", e);
            } catch (Throwable t) {
                error = t;
            } finally {
                if (producer != null) {
                    producer.close();
                }
            }
        }
    }

}
