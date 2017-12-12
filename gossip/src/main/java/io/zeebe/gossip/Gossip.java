/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.gossip;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.zeebe.clustering.gossip.GossipEventType;
import io.zeebe.gossip.dissemination.DisseminationComponent;
import io.zeebe.gossip.failuredetection.*;
import io.zeebe.gossip.membership.MembershipList;
import io.zeebe.gossip.protocol.*;
import io.zeebe.transport.*;
import io.zeebe.util.DeferredCommandContext;
import io.zeebe.util.actor.Actor;

public class Gossip implements Actor, GossipController
{
    private final SubscriptionController subscriptionController;
    private final PingController failureDetectionController;
    private final MembershipList memberList;

    private final PingReqEventHandler pingReqController;
    private final JoinController joinController;
    private final SuspictionController suspictionController;

    private final DeferredCommandContext deferredCommands = new DeferredCommandContext();

    public Gossip(
            final SocketAddress socketAddress,
            final BufferingServerTransport serverTransport,
            final ClientTransport clientTransport,
            final GossipConfiguration configuration)
    {
        memberList = new MembershipList(socketAddress, configuration);
        final DisseminationComponent disseminationComponent = new DisseminationComponent(configuration, memberList);

        final GossipEventFactory gossipEventFactory = new GossipEventFactory(memberList, disseminationComponent);

        final GossipEventSender gossipEventSender = new GossipEventSender(clientTransport, serverTransport, memberList, disseminationComponent, gossipEventFactory);

        final GossipContext context = new GossipContext(configuration, memberList, disseminationComponent, gossipEventSender, gossipEventFactory);

        failureDetectionController = new PingController(context);

        final PingEventHandler pingMessageHandler = new PingEventHandler(context);
        pingReqController = new PingReqEventHandler(context);
        final SyncRequestEventHandler syncRequestHandler = new SyncRequestEventHandler(context);

        final GossipRequestHandler requestHandler = new GossipRequestHandler(context, gossipEventFactory);
        requestHandler.registerGossipEventConsumer(GossipEventType.PING, pingMessageHandler);
        requestHandler.registerGossipEventConsumer(GossipEventType.PING_REQ, pingReqController);
        requestHandler.registerGossipEventConsumer(GossipEventType.SYNC_REQUEST, syncRequestHandler);

        joinController = new JoinController(context);
        suspictionController = new SuspictionController(context);

        subscriptionController = new SubscriptionController(serverTransport, requestHandler);
    }

    @Override
    public CompletableFuture<Void> join(List<SocketAddress> contactPoints)
    {
        return deferredCommands.runAsync(future -> joinController.join(contactPoints, future));
    }

    @Override
    public CompletableFuture<Void> leave()
    {
        return deferredCommands.runAsync(future ->
        {
            joinController.leave(future);

            future.thenAccept(v ->
            {
                // TODO clear / stop other stuff
            });
        });
    }

    @Override
    public int doWork() throws Exception
    {
        int workCount = 0;

        workCount += deferredCommands.doWork();
        workCount += joinController.doWork();

        workCount += subscriptionController.doWork();
        workCount += failureDetectionController.doWork();
        workCount += pingReqController.doWork();
        workCount += suspictionController.doWork();

        return workCount;
    }

    @Override
    public int getPriority(long now)
    {
        return PRIORITY_HIGH;
    }

    @Override
    public void addMembershipListener(GossipMembershipListener listener)
    {
        deferredCommands.runAsync(() -> memberList.addListener(listener));
    }

    @Override
    public void removeMembershipListener(GossipMembershipListener listener)
    {
        deferredCommands.runAsync(() -> memberList.removeListener(listener));
    }

}
