/*
 * Copyright 2013 - 2017 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elasticsoftware.elasticactors.cluster;

import org.elasticsoftware.elasticactors.ActorRef;
import org.elasticsoftware.elasticactors.ActorSystem;
import org.elasticsoftware.elasticactors.MessageDeliveryException;
import org.elasticsoftware.elasticactors.UnexpectedResponseTypeException;
import org.elasticsoftware.elasticactors.actors.ActorDelegate;
import org.elasticsoftware.elasticactors.actors.ReplyActor;

import java.util.concurrent.CompletableFuture;

/**
 * @author Joost van de Wijgerd
 */
public abstract class AbstractActorRef implements ActorRef {
    protected final InternalActorSystem actorSystem;

    public AbstractActorRef(InternalActorSystem actorSystem) {
        this.actorSystem = actorSystem;
    }

    public <T> CompletableFuture<T> ask(Object message, Class<T> responseType) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            ActorRef replyRef = actorSystem.tempActorOf(ReplyActor.class, new ActorDelegate<T>() {
                @Override
                public ActorDelegate<T> getBody() {
                    return this;
                }

                @Override
                public void onUndeliverable(ActorRef receiver, Object message) {
                    future.completeExceptionally(new MessageDeliveryException("Unable to deliver message", false));
                }

                @Override
                public void onReceive(ActorRef sender, Object message) {
                    if (responseType.isInstance(message)) {
                        future.complete((T) message);
                    } else if (message instanceof Throwable) {
                        future.completeExceptionally((Throwable) message);
                    } else {
                        future.completeExceptionally(new UnexpectedResponseTypeException("Receiver unexpectedly responsed with a message of type " + message.getClass().getTypeName()));
                    }
                }
            });
            tell(message, replyRef);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
