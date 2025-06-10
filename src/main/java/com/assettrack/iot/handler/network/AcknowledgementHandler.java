package com.assettrack.iot.handler.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class AcknowledgementHandler extends ChannelOutboundHandlerAdapter {

    public interface Event {
    }

    public static class EventReceived implements com.assettrack.iot.handler.network.AcknowledgementHandler.Event {
    }

    public static class EventDecoded implements com.assettrack.iot.handler.network.AcknowledgementHandler.Event {
        private final Collection<Object> objects;

        public EventDecoded(Collection<Object> objects) {
            this.objects = objects;
        }

        public Collection<Object> getObjects() {
            return objects;
        }
    }

    public static class EventHandled implements com.assettrack.iot.handler.network.AcknowledgementHandler.Event {
        private final Object object;

        public EventHandled(Object object) {
            this.object = object;
        }

        public Object getObject() {
            return object;
        }
    }

    private static final class Entry {
        private final Object message;
        private final ChannelPromise promise;

        private Entry(Object message, ChannelPromise promise) {
            this.message = message;
            this.promise = promise;
        }

        public Object getMessage() {
            return message;
        }

        public ChannelPromise getPromise() {
            return promise;
        }
    }

    private List<com.assettrack.iot.handler.network.AcknowledgementHandler.Entry> queue;
    private final Set<Object> waiting = new HashSet<>();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        List<com.assettrack.iot.handler.network.AcknowledgementHandler.Entry> output = new LinkedList<>();
        synchronized (this) {
            if (msg instanceof com.assettrack.iot.handler.network.AcknowledgementHandler.Event) {

                if (msg instanceof EventReceived) {
                    log.info("Event received");
                    if (queue == null) {
                        queue = new LinkedList<>();
                    }
                } else if (msg instanceof EventDecoded event) {
                    // Add null check for getObjects()
                    Collection<Object> objects = event.getObjects();
                    if (objects != null) {
                        log.info("Event decoded {}", objects.size());
                        waiting.addAll(objects);
                    } else {
                        log.warn("Received EventDecoded with null objects");
                    }
                } else if (msg instanceof EventHandled event) {
                    log.info("Event handled");
                    Object obj = event.getObject();
                    if (obj != null) {
                        waiting.remove(obj);
                    }
                }

                if (msg instanceof com.assettrack.iot.handler.network.AcknowledgementHandler.EventReceived) {
                    log.info("Event received");
                    if (queue == null) {
                        queue = new LinkedList<>();
                    }
                } else if (msg instanceof com.assettrack.iot.handler.network.AcknowledgementHandler.EventDecoded event) {
                    log.info("Event decoded {}", event.getObjects().size());
                    waiting.addAll(event.getObjects());
                } else if (msg instanceof com.assettrack.iot.handler.network.AcknowledgementHandler.EventHandled event) {
                    log.info("Event handled");
                    waiting.remove(event.getObject());
                }
                if (!(msg instanceof com.assettrack.iot.handler.network.AcknowledgementHandler.EventReceived) && waiting.isEmpty()) {
                    output.addAll(queue);
                    queue = null;
                }
            } else if (queue != null) {
                log.info("Message queued");
                queue.add(new com.assettrack.iot.handler.network.AcknowledgementHandler.Entry(msg, promise));
            } else {
                log.info("Message sent");
                output.add(new com.assettrack.iot.handler.network.AcknowledgementHandler.Entry(msg, promise));
            }
        }
        for (com.assettrack.iot.handler.network.AcknowledgementHandler.Entry entry : output) {
            ctx.write(entry.getMessage(), entry.getPromise());
        }
    }
}
