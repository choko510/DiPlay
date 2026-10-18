package android.hardware.usb;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

public final class FakeUsbDeviceConnection extends UsbDeviceConnection {
    private final ArrayDeque<Object> bulkOutResults = new ArrayDeque<>();
    private final ArrayDeque<byte[]> bulkInData = new ArrayDeque<>();
    private final ArrayDeque<WaitEvent> waitEvents = new ArrayDeque<>();
    private final List<byte[]> bulkOutTransfers = new ArrayList<>();
    private byte[] currentBulkIn;
    private int currentBulkInOffset;
    private volatile FakeUsbRequest queuedRequest;
    private boolean claimInterfaceResult;
    private boolean setInterfaceResult;
    private boolean closed;
    private int requestWaitCalls;

    public static FakeUsbEndpoint bulkIn(int endpointNumber, int maxPacketSize) {
        return new FakeUsbEndpoint(0x80 | (endpointNumber & 0x0f), maxPacketSize);
    }

    public static FakeUsbEndpoint bulkOut(int endpointNumber, int maxPacketSize) {
        return new FakeUsbEndpoint(endpointNumber & 0x0f, maxPacketSize);
    }

    public synchronized void enqueueBulkIn(byte[] data) {
        bulkInData.addLast(data.clone());
    }

    public synchronized void enqueueBulkOutResult(int result) {
        bulkOutResults.addLast(Integer.valueOf(result));
    }

    public synchronized void enqueueBulkOutException(RuntimeException error) {
        bulkOutResults.addLast(error);
    }

    public synchronized void enqueueWaitTimeout() {
        waitEvents.addLast(new WaitEvent(WaitKind.TIMEOUT, null, null));
    }

    public synchronized void enqueueWaitNull() {
        waitEvents.addLast(new WaitEvent(WaitKind.NULL_RESULT, null, null));
    }

    public synchronized void enqueueWaitCompletion(byte[] data) {
        waitEvents.addLast(new WaitEvent(WaitKind.COMPLETION, data.clone(), null));
    }

    public synchronized void enqueueUnexpectedCompletion(FakeUsbRequest request) {
        waitEvents.addLast(new WaitEvent(WaitKind.UNEXPECTED, null, request));
    }

    public synchronized void setClaimInterfaceResult(boolean result) {
        claimInterfaceResult = result;
    }

    public synchronized void setInterfaceResult(boolean result) {
        setInterfaceResult = result;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int getRequestWaitCalls() {
        return requestWaitCalls;
    }

    public synchronized List<byte[]> getBulkOutTransfers() {
        List<byte[]> result = new ArrayList<>(bulkOutTransfers.size());
        for (byte[] transfer : bulkOutTransfers) result.add(transfer.clone());
        return result;
    }

    @Override
    public synchronized boolean claimInterface(UsbInterface usbInterface, boolean force) {
        return claimInterfaceResult;
    }

    @Override
    public synchronized boolean releaseInterface(UsbInterface usbInterface) {
        return claimInterfaceResult;
    }

    @Override
    public synchronized boolean setInterface(UsbInterface usbInterface) {
        return setInterfaceResult;
    }

    @Override
    public synchronized int controlTransfer(
            int requestType,
            int request,
            int value,
            int index,
            byte[] buffer,
            int length,
            int timeout
    ) {
        return -1;
    }

    @Override
    public synchronized int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int length, int timeout) {
        if (endpoint.getDirection() == 0x80) return readBulkIn(buffer, length);
        Object next = bulkOutResults.pollFirst();
        if (next == null) return -1;
        if (next instanceof RuntimeException) throw (RuntimeException) next;
        int result = ((Integer) next).intValue();
        if (result > 0) bulkOutTransfers.add(Arrays.copyOf(buffer, Math.min(result, length)));
        return result;
    }

    private int readBulkIn(byte[] buffer, int length) {
        if (currentBulkIn == null || currentBulkInOffset == currentBulkIn.length) {
            currentBulkIn = bulkInData.pollFirst();
            currentBulkInOffset = 0;
        }
        if (currentBulkIn == null) return 0;
        int count = Math.min(length, currentBulkIn.length - currentBulkInOffset);
        System.arraycopy(currentBulkIn, currentBulkInOffset, buffer, 0, count);
        currentBulkInOffset += count;
        return count;
    }

    @Override
    public synchronized UsbRequest requestWait(long timeout) throws TimeoutException {
        requestWaitCalls++;
        WaitEvent event = waitEvents.pollFirst();
        if (event == null || event.kind == WaitKind.TIMEOUT) throw new TimeoutException("scripted timeout");
        if (event.kind == WaitKind.NULL_RESULT) return null;
        if (event.kind == WaitKind.UNEXPECTED) return event.request;
        if (queuedRequest == null) throw new IllegalStateException("no request is queued");
        queuedRequest.complete(event.data);
        return queuedRequest;
    }

    @Override
    public synchronized UsbRequest requestWait() {
        try {
            return requestWait(0);
        } catch (TimeoutException timeout) {
            return null;
        }
    }

    private void onRequestQueued(FakeUsbRequest request) {
        queuedRequest = request;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private enum WaitKind {
        COMPLETION,
        TIMEOUT,
        NULL_RESULT,
        UNEXPECTED,
    }

    private static final class WaitEvent {
        private final WaitKind kind;
        private final byte[] data;
        private final UsbRequest request;

        private WaitEvent(WaitKind kind, byte[] data, UsbRequest request) {
            this.kind = kind;
            this.data = data;
            this.request = request;
        }
    }

    public static final class FakeUsbEndpoint extends UsbEndpoint {
        private final int address;
        private final int maxPacketSize;

        private FakeUsbEndpoint(int address, int maxPacketSize) {
            if (maxPacketSize <= 0) throw new IllegalArgumentException("maxPacketSize must be positive");
            this.address = address;
            this.maxPacketSize = maxPacketSize;
        }

        @Override
        public int getAddress() {
            return address;
        }

        @Override
        public int getAttributes() {
            return 2;
        }

        @Override
        public int getDirection() {
            return address & 0x80;
        }

        @Override
        public int getEndpointNumber() {
            return address & 0x0f;
        }

        @Override
        public int getInterval() {
            return 0;
        }

        @Override
        public int getMaxPacketSize() {
            return maxPacketSize;
        }

        @Override
        public int getType() {
            return 2;
        }
    }

    public static final class FakeUsbRequest extends UsbRequest {
        private final ArrayDeque<Boolean> queueResults = new ArrayDeque<>();
        private final boolean initializeResult;
        private UsbDeviceConnection connection;
        private FakeUsbEndpoint endpoint;
        private Object clientData;
        private ByteBuffer queuedBuffer;
        private boolean closed;
        private int queueCalls;
        private int cancelCalls;

        public FakeUsbRequest(boolean initializeResult) {
            this.initializeResult = initializeResult;
        }

        public synchronized void enqueueQueueResult(boolean result) {
            queueResults.addLast(Boolean.valueOf(result));
        }

        public synchronized int getQueueCalls() {
            return queueCalls;
        }

        public synchronized int getQueuedBufferCapacity() {
            return queuedBuffer == null ? 0 : queuedBuffer.capacity();
        }

        public synchronized int getCancelCalls() {
            return cancelCalls;
        }

        public synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public synchronized boolean initialize(UsbDeviceConnection connection, UsbEndpoint endpoint) {
            this.connection = connection;
            this.endpoint = (FakeUsbEndpoint) endpoint;
            return initializeResult;
        }

        @Override
        public synchronized boolean queue(ByteBuffer buffer) {
            queueCalls++;
            queuedBuffer = buffer;
            Boolean result = queueResults.pollFirst();
            if (result == null || !result.booleanValue() || closed || connection == null) return false;
            ((FakeUsbDeviceConnection) connection).onRequestQueued(this);
            return true;
        }

        private synchronized void complete(byte[] data) {
            if (queuedBuffer == null) throw new IllegalStateException("request has no queued buffer");
            queuedBuffer.clear();
            queuedBuffer.put(data);
        }

        @Override
        public synchronized UsbEndpoint getEndpoint() {
            return endpoint;
        }

        @Override
        public synchronized boolean cancel() {
            cancelCalls++;
            return true;
        }

        @Override
        public synchronized void close() {
            closed = true;
        }

        @Override
        public synchronized Object getClientData() {
            return clientData;
        }

        @Override
        public synchronized void setClientData(Object clientData) {
            this.clientData = clientData;
        }
    }
}
