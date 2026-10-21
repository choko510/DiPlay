package android.hardware.usb;

// The Android USB connection/endpoint constructors are package-private; this fake remains test-only.
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ScriptedUsbDeviceConnection extends UsbDeviceConnection {
    public static final class Endpoint extends UsbEndpoint {
        private final int address;
        private final int attributes;
        private final int maxPacketSize;
        private final int interval;

        public Endpoint(int address, int attributes, int maxPacketSize, int interval) {
            super();
            this.address = address;
            this.attributes = attributes;
            this.maxPacketSize = maxPacketSize;
            this.interval = interval;
        }

        @Override public int getAddress() { return address; }
        @Override public int getAttributes() { return attributes; }
        @Override public int getDirection() { return address & UsbConstants.USB_ENDPOINT_DIR_MASK; }
        @Override public int getEndpointNumber() { return address & UsbConstants.USB_ENDPOINT_NUMBER_MASK; }
        @Override public int getInterval() { return interval; }
        @Override public int getMaxPacketSize() { return maxPacketSize; }
        @Override public int getType() { return attributes & UsbConstants.USB_ENDPOINT_XFERTYPE_MASK; }
    }

    public static final class BulkOutcome {
        private final int result;
        private final byte[] input;
        private final RuntimeException failure;
        private final long delayMillis;

        private BulkOutcome(int result, byte[] input, RuntimeException failure, long delayMillis) {
            this.result = result;
            this.input = input == null ? null : input.clone();
            this.failure = failure;
            this.delayMillis = delayMillis;
        }

        public static BulkOutcome data(byte[] bytes) {
            return new BulkOutcome(bytes.length, bytes, null, 0);
        }

        public static BulkOutcome partial(byte[] bytes, int transferred) {
            if (transferred < 0 || transferred > bytes.length) {
                throw new IllegalArgumentException("transferred must fit the supplied bytes");
            }
            return new BulkOutcome(transferred, bytes, null, 0);
        }

        public static BulkOutcome result(int result) {
            return new BulkOutcome(result, null, null, 0);
        }

        public static BulkOutcome noData(long delayMillis) {
            return new BulkOutcome(-1, null, null, delayMillis);
        }

        public static BulkOutcome failure(RuntimeException error) {
            if (error == null) throw new NullPointerException("error");
            return new BulkOutcome(-1, null, error, 0);
        }
    }

    public static final class TransferRecord {
        public final UsbEndpoint endpoint;
        public final byte[] bytes;
        public final int requestedLength;
        public final int transferred;

        private TransferRecord(UsbEndpoint endpoint, byte[] bytes, int requestedLength, int transferred) {
            this.endpoint = endpoint;
            this.bytes = bytes.clone();
            this.requestedLength = requestedLength;
            this.transferred = transferred;
        }
    }

    public static final class WaitOutcome {
        private enum Kind { COMPLETION, TIMEOUT, NULL_RESULT, UNEXPECTED, BLOCK_UNTIL_CLOSE }

        private final Kind kind;
        private final byte[] bytes;
        private final UsbRequest unexpectedRequest;

        private WaitOutcome(Kind kind, byte[] bytes, UsbRequest unexpectedRequest) {
            this.kind = kind;
            this.bytes = bytes == null ? null : bytes.clone();
            this.unexpectedRequest = unexpectedRequest;
        }

        public static WaitOutcome completion(byte[] bytes) {
            return new WaitOutcome(Kind.COMPLETION, bytes, null);
        }

        public static WaitOutcome timeout() {
            return new WaitOutcome(Kind.TIMEOUT, null, null);
        }

        public static WaitOutcome nullResult() {
            return new WaitOutcome(Kind.NULL_RESULT, null, null);
        }

        public static WaitOutcome unexpected(UsbRequest request) {
            return new WaitOutcome(Kind.UNEXPECTED, null, request);
        }

        public static WaitOutcome blockUntilClose() {
            return new WaitOutcome(Kind.BLOCK_UNTIL_CLOSE, null, null);
        }
    }

    private final Map<UsbEndpoint, ArrayDeque<BulkOutcome>> bulkOutcomes = new IdentityHashMap<>();
    private final Map<UsbEndpoint, BulkOutcome> bulkFallbacks = new IdentityHashMap<>();
    private final ArrayDeque<BulkOutcome> controlOutcomes = new ArrayDeque<>();
    private final ArrayDeque<Boolean> queueOutcomes = new ArrayDeque<>();
    private final ArrayDeque<WaitOutcome> waitOutcomes = new ArrayDeque<>();
    private final ArrayDeque<Boolean> claimOutcomes = new ArrayDeque<>();
    private final ArrayDeque<Boolean> setInterfaceOutcomes = new ArrayDeque<>();
    private final ArrayDeque<Boolean> releaseOutcomes = new ArrayDeque<>();
    private final List<TransferRecord> transfers = new ArrayList<>();
    private final CountDownLatch waitStarted = new CountDownLatch(1);
    private final CountDownLatch connectionClosed = new CountDownLatch(1);
    private byte[] rawDescriptors = new byte[0];
    private UsbRequest queuedRequest;
    private ByteBuffer queuedBuffer;
    private int queueAttempts;
    private int lastQueueCapacity;
    private int requestInitializations;
    private int cancelCalls;
    private int requestCloseCalls;
    private int connectionCloseCalls;

    public synchronized void enqueueBulkOutcome(UsbEndpoint endpoint, BulkOutcome outcome) {
        bulkOutcomes.computeIfAbsent(endpoint, ignored -> new ArrayDeque<>()).addLast(outcome);
    }

    public synchronized void setBulkFallback(UsbEndpoint endpoint, BulkOutcome outcome) {
        bulkFallbacks.put(endpoint, outcome);
    }

    public synchronized void enqueueControlOutcome(BulkOutcome outcome) {
        controlOutcomes.addLast(outcome);
    }

    public synchronized void enqueueQueueOutcome(boolean result) {
        queueOutcomes.addLast(result);
    }

    public synchronized void enqueueWaitOutcome(WaitOutcome outcome) {
        waitOutcomes.addLast(outcome);
    }

    public synchronized void enqueueClaimOutcome(boolean result) {
        claimOutcomes.addLast(result);
    }

    public synchronized void enqueueSetInterfaceOutcome(boolean result) {
        setInterfaceOutcomes.addLast(result);
    }

    public synchronized void enqueueReleaseOutcome(boolean result) {
        releaseOutcomes.addLast(result);
    }

    public synchronized void setRawDescriptors(byte[] descriptors) {
        rawDescriptors = descriptors.clone();
    }

    public synchronized List<TransferRecord> transferRecords() {
        return Collections.unmodifiableList(new ArrayList<>(transfers));
    }

    public synchronized int queueAttempts() { return queueAttempts; }
    public synchronized int lastQueueCapacity() { return lastQueueCapacity; }
    public synchronized int requestInitializations() { return requestInitializations; }
    public synchronized int cancelCalls() { return cancelCalls; }
    public synchronized int requestCloseCalls() { return requestCloseCalls; }
    public synchronized int connectionCloseCalls() { return connectionCloseCalls; }
    public synchronized UsbRequest queuedRequest() { return queuedRequest; }
    public synchronized ByteBuffer queuedBuffer() { return queuedBuffer; }
    public boolean awaitWaitStarted(long timeoutMillis) throws InterruptedException {
        return waitStarted.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized boolean initializeRequest(UsbRequest request, UsbEndpoint endpoint) {
        requestInitializations++;
        return true;
    }

    public synchronized boolean queueRequest(UsbRequest request, ByteBuffer buffer) {
        if (queueOutcomes.isEmpty()) throw new AssertionError("UsbRequest.queue outcome was not scripted");
        queueAttempts++;
        lastQueueCapacity = buffer.capacity();
        boolean accepted = queueOutcomes.removeFirst();
        if (accepted) {
            queuedRequest = request;
            queuedBuffer = buffer;
        }
        return accepted;
    }

    public synchronized boolean cancelRequest(UsbRequest request) {
        cancelCalls++;
        return request == queuedRequest;
    }

    public synchronized void closeRequest(UsbRequest request) {
        requestCloseCalls++;
    }

    @Override public int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int length, int timeout) {
        return runBulkTransfer(endpoint, buffer, length, timeout);
    }

    @Override public int bulkTransfer(UsbEndpoint endpoint, byte[] buffer, int length, int timeout, int flags) {
        return runBulkTransfer(endpoint, buffer, length, timeout);
    }

    private int runBulkTransfer(UsbEndpoint endpoint, byte[] buffer, int length, int timeout) {
        BulkOutcome outcome;
        synchronized (this) {
            ArrayDeque<BulkOutcome> queue = bulkOutcomes.get(endpoint);
            outcome = queue == null || queue.isEmpty() ? bulkFallbacks.get(endpoint) : queue.removeFirst();
            if (outcome == null) throw new AssertionError("bulkTransfer outcome was not scripted for endpoint " + endpoint.getAddress());
        }
        delay(outcome.delayMillis);
        if (outcome.failure != null) throw outcome.failure;
        if (outcome.result > length) throw new AssertionError("bulkTransfer result exceeds requested length");
        if (outcome.result > 0 && endpoint.getDirection() == UsbConstants.USB_DIR_IN && outcome.input == null) {
            throw new AssertionError("successful bulk-IN outcome must include bytes");
        }
        if (outcome.input != null && (endpoint.getDirection() & UsbConstants.USB_DIR_IN) != 0) {
            if (outcome.input.length < outcome.result) throw new AssertionError("scripted input is shorter than the result");
            System.arraycopy(outcome.input, 0, buffer, 0, outcome.result);
        }
        if (outcome.result > 0) {
            byte[] transferred = new byte[outcome.result];
            System.arraycopy(buffer, 0, transferred, 0, outcome.result);
            synchronized (this) {
                transfers.add(new TransferRecord(endpoint, transferred, length, outcome.result));
            }
        }
        return outcome.result;
    }

    @Override public int controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int length, int timeout) {
        return runControlTransfer(requestType, buffer, length);
    }

    @Override public int controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int length, int timeout, int flags) {
        return runControlTransfer(requestType, buffer, length);
    }

    private int runControlTransfer(int requestType, byte[] buffer, int length) {
        BulkOutcome outcome;
        synchronized (this) {
            if (controlOutcomes.isEmpty()) throw new AssertionError("controlTransfer outcome was not scripted");
            outcome = controlOutcomes.removeFirst();
        }
        if (outcome.failure != null) throw outcome.failure;
        if (outcome.result > length) throw new AssertionError("controlTransfer result exceeds requested length");
        boolean input = (requestType & UsbConstants.USB_DIR_IN) != 0;
        if (outcome.result > 0 && input && outcome.input == null) {
            throw new AssertionError("successful control-IN outcome must include bytes");
        }
        if (outcome.input != null && input && outcome.result > 0) {
            if (outcome.input.length < outcome.result || outcome.result > length) {
                throw new AssertionError("scripted control input does not fit the request");
            }
            System.arraycopy(outcome.input, 0, buffer, 0, outcome.result);
        }
        return outcome.result;
    }

    @Override public synchronized boolean claimInterface(UsbInterface usbInterface, boolean force) {
        if (claimOutcomes.isEmpty()) throw new AssertionError("claimInterface outcome was not scripted");
        return claimOutcomes.removeFirst();
    }

    @Override public synchronized boolean setInterface(UsbInterface usbInterface) {
        if (setInterfaceOutcomes.isEmpty()) throw new AssertionError("setInterface outcome was not scripted");
        return setInterfaceOutcomes.removeFirst();
    }

    @Override public synchronized boolean releaseInterface(UsbInterface usbInterface) {
        if (releaseOutcomes.isEmpty()) throw new AssertionError("releaseInterface outcome was not scripted");
        return releaseOutcomes.removeFirst();
    }

    @Override public synchronized byte[] getRawDescriptors() { return rawDescriptors.clone(); }

    @Override public UsbRequest requestWait(long timeoutMillis) throws TimeoutException {
        WaitOutcome outcome;
        synchronized (this) {
            if (waitOutcomes.isEmpty()) throw new AssertionError("requestWait outcome was not scripted");
            outcome = waitOutcomes.removeFirst();
        }
        switch (outcome.kind) {
            case TIMEOUT:
                delay(timeoutMillis);
                throw new TimeoutException("scripted requestWait timeout");
            case NULL_RESULT:
                return null;
            case COMPLETION:
                synchronized (this) {
                    if (queuedRequest == null || queuedBuffer == null) throw new AssertionError("completion has no queued request");
                    if (outcome.bytes.length > queuedBuffer.remaining()) throw new AssertionError("completion exceeds queued buffer");
                    queuedBuffer.put(outcome.bytes);
                    return queuedRequest;
                }
            case UNEXPECTED:
                return outcome.unexpectedRequest == null ? new UsbRequest() : outcome.unexpectedRequest;
            case BLOCK_UNTIL_CLOSE:
                waitStarted.countDown();
                try {
                    if (!connectionClosed.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        throw new TimeoutException("scripted blocked requestWait timeout");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new TimeoutException("scripted requestWait interrupted");
                }
                return null;
            default:
                throw new AssertionError("unsupported requestWait outcome");
        }
    }

    @Override public UsbRequest requestWait() {
        try {
            return requestWait(0);
        } catch (TimeoutException error) {
            throw new AssertionError(error);
        }
    }

    @Override public void close() {
        synchronized (this) { connectionCloseCalls++; }
        connectionClosed.countDown();
    }

    private static void delay(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
