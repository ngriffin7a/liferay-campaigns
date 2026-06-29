// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns;

import com.liferay.client.extension.util.spring.boot3.ClientExtensionUtilSpringBootComponentScan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@Import(ClientExtensionUtilSpringBootComponentScan.class)
@SpringBootApplication
public class CampaignsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampaignsApplication.class, args);
    }

}
