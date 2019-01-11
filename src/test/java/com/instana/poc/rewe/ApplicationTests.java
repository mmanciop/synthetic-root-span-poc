package com.instana.poc.rewe;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(properties = { "instana_agent_host=localhost", "instana_agent_port:42699" })
public class ApplicationTests {

	@Test
	public void contextLoads() {
	}

}

