package com.shopease.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

@Service
public class SnsService {

    private final SnsClient snsClient;
    private final String topicArn;

    public SnsService(@Value("${aws.region}") String region,
                      @Value("${aws.sns.topic-arn}") String topicArn) {
        this.topicArn = topicArn;
        this.snsClient = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    public void publishSignupEvent(String name, String email) {
        String message = String.format("{\"name\":\"%s\",\"email\":\"%s\"}", name, email);

        PublishRequest request = PublishRequest.builder()
                .topicArn(topicArn)
                .message(message)
                .subject("New User Signup")
                .build();

        PublishResponse response = snsClient.publish(request);
        System.out.println("SNS Message published. MessageId: " + response.messageId());
    }
}
