package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.TicketVerificationResult;

public interface TicketVerificationProvider {
    ProviderMetadata getMetadata();
    TicketVerificationResult verifyTicket(String pnr, String ticketNumber);
}
