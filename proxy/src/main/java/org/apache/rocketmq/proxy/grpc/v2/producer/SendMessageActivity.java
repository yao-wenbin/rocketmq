/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.proxy.grpc.v2.producer;

import apache.rocketmq.v2.Code;
import apache.rocketmq.v2.Encoding;
import apache.rocketmq.v2.MessageType;
import apache.rocketmq.v2.Resource;
import apache.rocketmq.v2.SendMessageRequest;
import apache.rocketmq.v2.SendMessageResponse;
import apache.rocketmq.v2.SendResultEntry;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.proxy.common.ProxyContext;
import org.apache.rocketmq.proxy.config.ConfigurationManager;
import org.apache.rocketmq.proxy.grpc.v2.AbstractMessingActivity;
import org.apache.rocketmq.proxy.grpc.v2.channel.GrpcChannelManager;
import org.apache.rocketmq.proxy.grpc.v2.common.GrpcClientSettingsManager;
import org.apache.rocketmq.proxy.grpc.v2.common.GrpcConverter;
import org.apache.rocketmq.proxy.grpc.v2.common.GrpcProxyException;
import org.apache.rocketmq.proxy.grpc.v2.common.ResponseBuilder;
import org.apache.rocketmq.proxy.processor.MessagingProcessor;
import org.apache.rocketmq.proxy.processor.QueueSelector;
import org.apache.rocketmq.proxy.service.route.AddressableMessageQueue;
import org.apache.rocketmq.proxy.service.route.MessageQueueView;

public class SendMessageActivity extends AbstractMessingActivity {

    public SendMessageActivity(MessagingProcessor messagingProcessor,
        GrpcClientSettingsManager grpcClientSettingsManager, GrpcChannelManager grpcChannelManager) {
        super(messagingProcessor, grpcClientSettingsManager, grpcChannelManager);
    }

    public CompletableFuture<SendMessageResponse> sendMessage(ProxyContext ctx, SendMessageRequest request) {
        CompletableFuture<SendMessageResponse> future = new CompletableFuture<>();

        try {
            if (request.getMessagesCount() <= 0) {
                throw new GrpcProxyException(Code.MESSAGE_CORRUPTED, "no message to send");
            }

            List<apache.rocketmq.v2.Message> messageList = request.getMessagesList();
            apache.rocketmq.v2.Message message = messageList.get(0);
            Resource topic = message.getTopic();
            validateTopic(topic);

            future = this.messagingProcessor.sendMessage(
                ctx,
                new SendMessageQueueSelector(request),
                GrpcConverter.getInstance().wrapResourceWithNamespace(topic),
                buildSysFlag(message),
                buildMessage(ctx, request.getMessagesList(), topic)
            ).thenApply(result -> convertToSendMessageResponse(ctx, request, result));
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    protected List<Message> buildMessage(ProxyContext context, List<apache.rocketmq.v2.Message> protoMessageList,
        Resource topic) {
        String topicName = GrpcConverter.getInstance().wrapResourceWithNamespace(topic);
        List<Message> messageExtList = new ArrayList<>();
        for (apache.rocketmq.v2.Message protoMessage : protoMessageList) {
            if (!protoMessage.getTopic().equals(topic)) {
                throw new GrpcProxyException(Code.MESSAGE_CORRUPTED, "topic in message is not same");
            }
            // here use topicName as producerGroup for transactional checker.
            messageExtList.add(buildMessage(context, protoMessage, topicName));
        }
        return messageExtList;
    }

    protected Message buildMessage(ProxyContext context, apache.rocketmq.v2.Message protoMessage, String producerGroup) {
        String topicName = GrpcConverter.getInstance().wrapResourceWithNamespace(protoMessage.getTopic());

        validateMessageBodySize(protoMessage.getBody());
        Message messageExt = new Message();
        messageExt.setTopic(topicName);
        messageExt.setBody(protoMessage.getBody().toByteArray());
        Map<String, String> messageProperty = this.buildMessageProperty(context, protoMessage, producerGroup);

        MessageAccessor.setProperties(messageExt, messageProperty);
        return messageExt;
    }

    protected int buildSysFlag(apache.rocketmq.v2.Message protoMessage) {
        // sysFlag (body encoding & message type)
        int sysFlag = 0;
        Encoding bodyEncoding = protoMessage.getSystemProperties().getBodyEncoding();
        if (bodyEncoding.equals(Encoding.GZIP)) {
            sysFlag |= MessageSysFlag.COMPRESSED_FLAG;
        }
        // transaction
        MessageType messageType = protoMessage.getSystemProperties().getMessageType();
        if (messageType.equals(MessageType.TRANSACTION)) {
            sysFlag |= MessageSysFlag.TRANSACTION_PREPARED_TYPE;
        }
        return sysFlag;
    }

    protected void validateMessageBodySize(ByteString body) {
        int max = ConfigurationManager.getProxyConfig().getMaxMessageSize();
        if (max <= 0) {
            return;
        }
        if (body.size() > max) {
            throw new GrpcProxyException(Code.MESSAGE_BODY_TOO_LARGE, "message body cannot exceed the max " + max);
        }
    }

    protected void validateTag(String tag) {
        if (StringUtils.isNotEmpty(tag)) {
            if (StringUtils.isBlank(tag)) {
                throw new GrpcProxyException(Code.ILLEGAL_MESSAGE_TAG, "tag cannot be the char sequence of whitespace");
            }
            if (tag.contains("|")) {
                throw new GrpcProxyException(Code.ILLEGAL_MESSAGE_TAG, "tag cannot contain '|'");
            }
        }
    }

    protected void validateMessageKey(String key) {
        if (StringUtils.isNotEmpty(key)) {
            if (StringUtils.isBlank(key)) {
                throw new GrpcProxyException(Code.ILLEGAL_MESSAGE_KEY, "key cannot be the char sequence of whitespace");
            }
        }
    }

    protected void validateMessageGroup(String messageGroup) {
        if (StringUtils.isNotEmpty(messageGroup)) {
            if (StringUtils.isBlank(messageGroup)) {
                throw new GrpcProxyException(Code.ILLEGAL_MESSAGE_GROUP, "message group cannot be the char sequence of whitespace");
            }
            int maxSize = ConfigurationManager.getProxyConfig().getMaxMessageGroupSize();
            if (maxSize <= 0) {
                return;
            }
            if (messageGroup.length() >= maxSize) {
                throw new GrpcProxyException(Code.ILLEGAL_MESSAGE_GROUP, "message group exceed the max size " + maxSize);
            }
        }
    }

    protected void validateMessagePropertySize(Map<String, String> property) {
        int maxSize = ConfigurationManager.getProxyConfig().getMaxUserPropertySize();
        if (maxSize <= 0) {
            return;
        }
        int curSize = 0;
        for (Map.Entry<String, String> entry : property.entrySet()) {
            curSize += entry.getKey().length();
            curSize += entry.getValue().length();
            if (curSize > maxSize) {
                throw new GrpcProxyException(Code.MESSAGE_PROPERTIES_TOO_LARGE, "the size of message properties cannot exceed the max " + maxSize);
            }
        }
    }

    protected Map<String, String> buildMessageProperty(ProxyContext context, apache.rocketmq.v2.Message message, String producerGroup) {
        org.apache.rocketmq.common.message.Message messageWithHeader = new org.apache.rocketmq.common.message.Message();
        // set user properties
        Map<String, String> userProperties = message.getUserPropertiesMap();
        for (Map.Entry<String, String> userPropertiesEntry : userProperties.entrySet()) {
            if (MessageConst.STRING_HASH_SET.contains(userPropertiesEntry.getKey())) {
                throw new GrpcProxyException(Code.ILLEGAL_MESSAGE_PROPERTY_KEY, "property is used by system: " + userPropertiesEntry.getKey());
            }
        }
        MessageAccessor.setProperties(messageWithHeader, Maps.newHashMap(userProperties));

        // set tag
        String tag = message.getSystemProperties().getTag();
        validateTag(tag);
        messageWithHeader.setTags(tag);

        // set keys
        List<String> keysList = message.getSystemProperties().getKeysList();
        for (String key : keysList) {
            validateMessageKey(key);
        }
        if (keysList.size() > 0) {
            messageWithHeader.setKeys(keysList);
        }

        // set message id
        String messageId = message.getSystemProperties().getMessageId();
        if (StringUtils.isBlank(messageId)) {
            throw new GrpcProxyException(Code.ILLEGAL_MESSAGE_ID, "message id cannot be empty");
        }
        MessageAccessor.putProperty(messageWithHeader, MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX, messageId);

        // set transaction property
        MessageType messageType = message.getSystemProperties().getMessageType();
        if (messageType.equals(MessageType.TRANSACTION)) {
            MessageAccessor.putProperty(messageWithHeader, MessageConst.PROPERTY_TRANSACTION_PREPARED, "true");

            if (message.getSystemProperties().hasOrphanedTransactionRecoveryDuration()) {
                MessageAccessor.putProperty(messageWithHeader, MessageConst.PROPERTY_CHECK_IMMUNITY_TIME_IN_SECONDS,
                    String.valueOf(Durations.toSeconds(message.getSystemProperties().getOrphanedTransactionRecoveryDuration())));
            }
        }

        // set delay level or deliver timestamp
        if (message.getSystemProperties().hasDeliveryTimestamp()) {
            Timestamp deliveryTimestamp = message.getSystemProperties().getDeliveryTimestamp();
            String timestampString = String.valueOf(Timestamps.toMillis(deliveryTimestamp));
            MessageAccessor.putProperty(messageWithHeader, MessageConst.PROPERTY_TIMER_DELIVER_MS, timestampString);
        }

        // set reconsume times
        int reconsumeTimes = message.getSystemProperties().getDeliveryAttempt();
        MessageAccessor.setReconsumeTime(messageWithHeader, String.valueOf(reconsumeTimes));
        // set producer group
        MessageAccessor.putProperty(messageWithHeader, MessageConst.PROPERTY_PRODUCER_GROUP, producerGroup);
        // set message group
        String messageGroup = message.getSystemProperties().getMessageGroup();
        if (StringUtils.isNotEmpty(messageGroup)) {
            validateMessageGroup(messageGroup);
            MessageAccessor.putProperty(messageWithHeader, MessageConst.PROPERTY_SHARDING_KEY, messageGroup);
        }
        // set trace context
        String traceContext = message.getSystemProperties().getTraceContext();
        if (!traceContext.isEmpty()) {
            MessageAccessor.putProperty(messageWithHeader, MessageConst.PROPERTY_TRACE_CONTEXT, traceContext);
        }

        String bornHost = message.getSystemProperties().getBornHost();
        if (StringUtils.isBlank(bornHost)) {
            bornHost = context.getRemoteAddress();
        }
        if (StringUtils.isNotBlank(bornHost)) {
            MessageAccessor.putProperty(messageWithHeader, MessageConst.PROPERTY_BORN_HOST, bornHost);
        }

        validateMessagePropertySize(messageWithHeader.getProperties());
        return messageWithHeader.getProperties();
    }

    protected SendMessageResponse convertToSendMessageResponse(ProxyContext ctx, SendMessageRequest request,
        List<SendResult> resultList) {
        SendMessageResponse.Builder builder = SendMessageResponse.newBuilder();

        Set<Code> responseCodes = new HashSet<>();
        for (SendResult result : resultList) {
            SendResultEntry resultEntry;
            switch (result.getSendStatus()) {
                case FLUSH_DISK_TIMEOUT:
                    resultEntry = SendResultEntry.newBuilder()
                        .setStatus(ResponseBuilder.getInstance().buildStatus(Code.MASTER_PERSISTENCE_TIMEOUT, "send message failed, sendStatus=" + result.getSendStatus()))
                        .build();
                    break;
                case FLUSH_SLAVE_TIMEOUT:
                    resultEntry = SendResultEntry.newBuilder()
                        .setStatus(ResponseBuilder.getInstance().buildStatus(Code.SLAVE_PERSISTENCE_TIMEOUT, "send message failed, sendStatus=" + result.getSendStatus()))
                        .build();
                    break;
                case SLAVE_NOT_AVAILABLE:
                    resultEntry = SendResultEntry.newBuilder()
                        .setStatus(ResponseBuilder.getInstance().buildStatus(Code.HA_NOT_AVAILABLE, "send message failed, sendStatus=" + result.getSendStatus()))
                        .build();
                    break;
                case SEND_OK:
                    resultEntry = SendResultEntry.newBuilder()
                        .setStatus(ResponseBuilder.getInstance().buildStatus(Code.OK, Code.OK.name()))
                        .setOffset(result.getQueueOffset())
                        .setMessageId(StringUtils.defaultString(result.getMsgId()))
                        .setTransactionId(StringUtils.defaultString(result.getTransactionId()))
                        .build();
                    break;
                default:
                    resultEntry = SendResultEntry.newBuilder()
                        .setStatus(ResponseBuilder.getInstance().buildStatus(Code.INTERNAL_SERVER_ERROR, "send message failed, sendStatus=" + result.getSendStatus()))
                        .build();
                    break;
            }
            builder.addEntries(resultEntry);
            responseCodes.add(resultEntry.getStatus().getCode());
        }
        if (responseCodes.size() > 1) {
            builder.setStatus(ResponseBuilder.getInstance().buildStatus(Code.MULTIPLE_RESULTS, Code.MULTIPLE_RESULTS.name()));
        } else if (responseCodes.size() == 1) {
            Code code = responseCodes.stream().findAny().get();
            builder.setStatus(ResponseBuilder.getInstance().buildStatus(code, code.name()));
        } else {
            builder.setStatus(ResponseBuilder.getInstance().buildStatus(Code.INTERNAL_SERVER_ERROR, "send status is empty"));
        }
        return builder.build();
    }

    protected static class SendMessageQueueSelector implements QueueSelector {

        private final SendMessageRequest request;

        public SendMessageQueueSelector(SendMessageRequest request) {
            this.request = request;
        }

        @Override
        public AddressableMessageQueue select(ProxyContext ctx, MessageQueueView messageQueueView) {
            try {
                apache.rocketmq.v2.Message message = request.getMessages(0);
                String shardingKey = null;
                if (request.getMessagesCount() == 1) {
                    shardingKey = message.getSystemProperties().getMessageGroup();
                }
                AddressableMessageQueue targetMessageQueue;
                if (StringUtils.isNotEmpty(shardingKey)) {
                    // With shardingKey
                    List<AddressableMessageQueue> writeQueues = messageQueueView.getWriteSelector().getQueues();
                    int bucket = Hashing.consistentHash(shardingKey.hashCode(), writeQueues.size());
                    targetMessageQueue = writeQueues.get(bucket);
                } else {
                    targetMessageQueue = messageQueueView.getWriteSelector().selectOne(false);
                }
                return targetMessageQueue;
            } catch (Exception e) {
                return null;
            }
        }
    }

}
