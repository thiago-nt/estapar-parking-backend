package br.com.estapar.parking.application;

public record GarageSyncResult(
        int sectorsReceived,
        int spotsReceived,
        int sectorsInserted,
        int sectorsUpdated,
        int spotsInserted,
        int spotsUpdated
) {
}
