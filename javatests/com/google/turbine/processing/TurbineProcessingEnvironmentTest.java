package com.google.turbine.processing;

import org.junit.Test;

import javax.annotation.processing.ProcessingEnvironment;
import java.util.HashMap;
import java.util.Map;

public class TurbineProcessingEnvironmentTest {

    @Test(expected = IllegalStateException.class)
    public void testAddStatisticsThrowsException(){

        Map<String, byte[]> settings = new HashMap<>();
        settings.put("Test",new byte[3]);
        TurbineProcessingEnvironment obj = new TurbineProcessingEnvironment(null,null,
                null,null,null,null,null,settings);
        byte[] extension = new byte[7];
        obj.addStatistics("Test", extension);

    }
}
