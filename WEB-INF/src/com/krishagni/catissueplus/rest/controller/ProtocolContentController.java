package com.krishagni.catissueplus.rest.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.common.service.impl.ProtocolContentService;

@Controller
@RequestMapping("/protocol-content")
public class ProtocolContentController {

	@Autowired
	private ProtocolContentService service;

	@GetMapping("/options")
	@ResponseBody
	public List<Map<String, Object>> options(@RequestParam("cpId") Long cpId) {
		return ResponseEvent.unwrap(service.getOptions(cpId));
	}

	@GetMapping("/access")
	@ResponseBody
	public Map<String, Object> access(@RequestParam("cpId") Long cpId) {
		return ResponseEvent.unwrap(service.getAccess(cpId));
	}
}
