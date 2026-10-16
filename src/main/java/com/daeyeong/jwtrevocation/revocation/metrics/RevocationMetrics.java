package com.daeyeong.jwtrevocation.revocation.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class RevocationMetrics {

    private final AtomicLong dbQueryCount = new AtomicLong(0);

    public void incrementDbQueryCount (){
        this.dbQueryCount.incrementAndGet();
    }

    public long getDbQueryCount(){
        return this.dbQueryCount.get();
    }

    public void reset(){
        this.dbQueryCount.set(0);
    }

}
