

package com.refund.routing.registry;

import com.refund.routing.model.RefundChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store of live {@link ChannelMetadata} for all refund channels.
 *
 * <p>Uses {@link ConcurrentHashMap} for lock-free reads. Reads dominate (every
 * routing request reads channel metadata); writes ({@link #register}) are rare
 * operational updates.
 *
 * <p>Default channel values on construction:
 * <pre>
 *   WALLET_CREDIT            successRate=0.95  cost= 2.0  settlementHrs= 0.5
 *   UPI                      successRate=0.92  cost= 3.0  settlementHrs= 1.0
 *   ORIGINAL_PAYMENT_METHOD  successRate=0.88  cost= 5.0  settlementHrs= 2.0
 *   BANK_TRANSFER            successRate=0.97  cost= 8.0  settlementHrs=24.0
 *   MANUAL_REVIEW            successRate=1.00  cost=15.0  settlementHrs=48.0
 * </pre>
 */
public final class ChannelRegistry {

    private final ConcurrentHashMap<String, ChannelMetadata> registry =
            new ConcurrentHashMap<>();

    /** Constructs a registry pre-populated with default channel metadata. */
    public ChannelRegistry() {
        registerDefaults();
    }

    // ─── Write ────────────────────────────────────────────────────────────────

    /**
     * Registers or atomically replaces the metadata for a channel.
     * Thread-safe — safe to call at any time, even while routing is in progress.
     */
    public void register(ChannelMetadata metadata) {
        registry.put(metadata.channel.name(), metadata);
    }

    /**
     * Convenience method to toggle a channel's availability without replacing
     * all other metadata fields.
     */
    public void setAvailability(RefundChannel channel, boolean available) {
        registry.compute(channel.name(), (key, existing) -> {
            if (existing == null) return null;
            return existing.withAvailability(available);
        });
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    /**
     * Returns the metadata for the given channel, or {@code null} if not registered.
     */
    public ChannelMetadata getMetadata(RefundChannel channel) {
        return registry.get(channel.name());
    }

    /**
     * Returns a snapshot list of all channels whose {@code available} flag is
     * {@code true}. The list is a fresh copy — safe to iterate without locking.
     */
    public List<ChannelMetadata> getAvailableChannels() {
        List<ChannelMetadata> result = new ArrayList<>();
        for (ChannelMetadata m : registry.values()) {
            if (m.available) result.add(m);
        }
        return result;
    }

    /** Returns a snapshot of all registered channels regardless of availability. */
    public List<ChannelMetadata> getAllChannels() {
        return new ArrayList<>(registry.values());
    }

    // ─── Defaults ─────────────────────────────────────────────────────────────

    private void registerDefaults() {
        register(new ChannelMetadata(RefundChannel.WALLET_CREDIT,           0.95, 2.0,  0.5,  true));
        register(new ChannelMetadata(RefundChannel.UPI,                     0.92, 3.0,  1.0,  true));
        register(new ChannelMetadata(RefundChannel.ORIGINAL_PAYMENT_METHOD, 0.88, 5.0,  2.0,  true));
        register(new ChannelMetadata(RefundChannel.BANK_TRANSFER,           0.97, 8.0,  24.0, true));
        register(new ChannelMetadata(RefundChannel.MANUAL_REVIEW,           1.00, 15.0, 48.0, true));
    }
}