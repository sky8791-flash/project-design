package com.collabdoc.pattern.observer;

import com.collabdoc.dto.ContentAppliedEvent;
import com.collabdoc.websocket.CollabBus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One observer per document, not per connection. Reference counting keeps the attach/detach balanced
 * without racing: the document keeps exactly one fan-out path while any session is attached, and a
 * session arriving while another leaves can neither orphan the observer nor leave a dead one behind.
 */
@Component
public class DocumentSubjectImpl implements DocumentSubject {

    private static final class Registration {
        private final DocumentObserver observer;
        private int sessions;

        private Registration(DocumentObserver observer) {
            this.observer = observer;
            this.sessions = 1;
        }
    }

    private final CollabBus bus;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    public DocumentSubjectImpl(CollabBus bus) {
        this.bus = bus;
    }

    @Override
    public void attach(DocumentObserver observer) {
        registrations.compute(observer.getDocumentId(), (key, existing) -> {
            if (existing == null) return new Registration(observer);
            existing.sessions++;
            return existing;
        });
    }

    @Override
    public void detach(String documentId) {
        registrations.computeIfPresent(documentId, (key, existing) ->
                --existing.sessions <= 0 ? null : existing);
    }

    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void notifyAllObservers(ContentAppliedEvent event) {
        String documentId = String.valueOf(event.getDocumentId());
        Registration registration = registrations.get(documentId);
        if (registration != null) {
            registration.observer.update(event);
            return;
        }
        // An event must reach the bus even when this process holds no viewer for the document. With two
        // instances behind a load balancer a rename or a restore is served by whichever node took the HTTP
        // call, which is often not the one holding the sockets — skipping it here would drop the frame for
        // exactly the clients it was meant for.
        new WebSocketObserver(bus, documentId).update(event);
    }
}
