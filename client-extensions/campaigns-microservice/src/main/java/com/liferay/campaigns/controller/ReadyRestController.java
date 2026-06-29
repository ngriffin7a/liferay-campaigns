// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.controller;


import com.liferay.client.extension.util.spring.boot3.BaseRestController;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/ready")
@RestController
public class ReadyRestController extends BaseRestController {

	@GetMapping
	public String get() {
		return "READY";
	}

}