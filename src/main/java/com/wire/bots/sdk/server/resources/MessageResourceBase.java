package com.wire.bots.sdk.server.resources;

import com.waz.model.Messages;
import com.wire.bots.sdk.*;
import com.wire.bots.sdk.models.otr.PreKey;
import com.wire.bots.sdk.server.GenericMessageProcessor;
import com.wire.bots.sdk.server.model.InboundMessage;

import java.util.ArrayList;
import java.util.Collections;

public abstract class MessageResourceBase {

    protected final MessageHandlerBase handler;
    protected final Configuration conf;
    protected final ClientRepo repo;

    public MessageResourceBase(MessageHandlerBase handler, Configuration conf, ClientRepo repo) {
        this.handler = handler;
        this.conf = conf;
        this.repo = repo;
    }

    protected void handleMessage(InboundMessage inbound, WireClient client) throws Exception {
        InboundMessage.Data data = inbound.data;
        switch (inbound.type) {
            case "conversation.otr-message-add": {
                GenericMessageProcessor processor = new GenericMessageProcessor(client, handler);

                byte[] bytes = client.decrypt(inbound.from, data.sender, data.text);
                Messages.GenericMessage genericMessage = Messages.GenericMessage.parseFrom(bytes);

                handler.onEvent(client, inbound.from, genericMessage);

                boolean processed = processor.process(inbound.from, genericMessage);
                if (processed) {
                    sendDeliveryReceipt(client, genericMessage.getMessageId());
                }
            }
            break;
            case "conversation.member-join": {
                String botId = client.getId();
                //Logger.info("conversation.member-join: bot: %s", botId);

                // Check if this bot got added to the conversation
                if (data.userIds.remove(botId)) {
                    handler.onNewConversation(client);
                }

                int minAvailable = 8 * data.userIds.size();
                if (minAvailable > 0) {
                    ArrayList<Integer> availablePrekeys = client.getAvailablePrekeys();
                    availablePrekeys.remove(new Integer(65535));  //remove last prekey
                    if (availablePrekeys.size() < minAvailable) {
                        Integer lastKeyOffset = Collections.max(availablePrekeys);
                        ArrayList<PreKey> keys = client.newPreKeys(lastKeyOffset + 1, minAvailable);
                        client.uploadPreKeys(keys);
                        Logger.info("Uploaded " + keys.size() + " prekeys");
                    }
                    handler.onMemberJoin(client, data.userIds);
                }
            }
            break;
            case "conversation.member-leave": {
                String botId = client.getId();
                //Logger.info("conversation.member-leave: bot: %s", botId);

                // Check if this bot got removed from the conversation
                if (data.userIds.remove(botId)) {
                    repo.removeClient(botId);
                    handler.onBotRemoved(botId);
                    repo.purgeBot(botId);
                }

                if (!data.userIds.isEmpty()) {
                    handler.onMemberLeave(client, data.userIds);
                }
            }
            break;
            case "conversation.delete": {
                String botId = client.getId();
                Logger.info("conversation.delete: bot: %s", botId);

                // Cleanup
                repo.removeClient(botId);
                handler.onBotRemoved(botId);
                repo.purgeBot(botId);
            }
            break;
            // Legacy code starts here
            case "user.connection": {
                if (inbound.connection.status.equals("pending")) {
                    client.acceptConnection(inbound.connection.to);
                }
            }
            break;
            case "conversation.create": {
                handler.onNewConversation(client);
            }
            break;
            // Legacy code ends here
            default:
                Logger.warning("Unknown event: %s, bot: %s", inbound.type, client.getId());
                break;
        }
    }

    private void sendDeliveryReceipt(WireClient client, String messageId) {
        try {
            client.sendDelivery(messageId);
        } catch (Exception e) {
            Logger.warning("sendDeliveryReceipt: " + e.getMessage());
        }
    }
}
