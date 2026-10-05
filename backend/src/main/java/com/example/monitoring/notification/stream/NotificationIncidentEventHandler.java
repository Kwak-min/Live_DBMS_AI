package com.example.monitoring.notification.stream;

import com.example.monitoring.common.stream.StreamRecord;
import com.example.monitoring.common.stream.StreamRecordHandler;
import com.example.monitoring.notification.scheduling.NotificationSchedulingStore;
import com.example.monitoring.notification.scheduling.NotificationSchedulingTransaction;
import org.springframework.stereotype.Component;

@Component
public final class NotificationIncidentEventHandler implements StreamRecordHandler {

    private final NotificationIncidentEventParser parser;
    private final NotificationSchedulingTransaction transaction;
    private final NotificationSchedulingStore store;

    public NotificationIncidentEventHandler(
            NotificationIncidentEventParser parser,
            NotificationSchedulingTransaction transaction,
            NotificationSchedulingStore store
    ) {
        this.parser = parser;
        this.transaction = transaction;
        this.store = store;
    }

    @Override
    public void verifyPrerequisite() {
        store.verifyPrerequisites();
    }

    @Override
    public void handle(StreamRecord record) {
        NotificationIncidentEvent event = parser.parse(record.payload());
        transaction.process(record.sourceStream(), event);
    }
}
