package com.krishagni.catissueplus.rest.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenNavigationService;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;

@Controller
@RequestMapping("/specimens/navigation")
public class SpecimenNavigationController {
	@Autowired private SpecimenNavigationService service;
	@GetMapping @ResponseBody
	public Map<String, Object> registrations(@RequestParam("cpId") Long cpId,
		@RequestParam(value="startAt", defaultValue="0") int startAt, @RequestParam(value="maxResults", defaultValue="100") int maxResults,
		@RequestParam(value="ppid", required=false) String ppid, @RequestParam(value="descending", defaultValue="true") boolean descending,
		@RequestParam(value="includeCount", defaultValue="false") boolean includeCount) {
		return ResponseEvent.unwrap(service.getRegistrations(cpId, startAt, maxResults, ppid, descending, includeCount));
	}
	@GetMapping("/{cprId}") @ResponseBody
	public Map<String, Object> registration(@PathVariable("cprId") Long cprId, @RequestParam("cpId") Long cpId,
		@RequestParam(value="startAt", defaultValue="0") int startAt, @RequestParam(value="maxResults", defaultValue="50") int maxResults) {
		return ResponseEvent.unwrap(service.getRegistration(cpId, cprId, startAt, maxResults));
	}
}
