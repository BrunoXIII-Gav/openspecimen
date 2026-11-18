package com.krishagni.catissueplus.rest.controller;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenReceiptInput;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenMatrixInput;
import com.krishagni.catissueplus.core.biospecimen.services.impl.SpecimenWorkflowService;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;

@Controller
@RequestMapping("/specimens")
public class SpecimenWorkflowController {
	@Autowired private SpecimenWorkflowService service;
	@GetMapping("/{id}/authorized-context") @ResponseBody
	public Map<String, Object> context(@PathVariable("id") Long id) { return ResponseEvent.unwrap(service.getContext(id)); }
	@GetMapping("/matrix-context") @ResponseBody
	public Map<String, Object> matrixContext(@RequestParam("cpId") Long cpId, @RequestParam("visitId") Long visitId) {
		return ResponseEvent.unwrap(service.getMatrixContext(cpId, visitId));
	}
	@PostMapping("/matrix") @ResponseBody
	public Boolean saveMatrix(@RequestBody SpecimenMatrixInput input) { return ResponseEvent.unwrap(service.saveMatrix(input)); }
	@GetMapping("/pending-receipt") @ResponseBody
	public Map<String, Object> pending(@RequestParam("cpId") Long cpId,
		@RequestParam(value="visitId", required=false) Long visitId, @RequestParam(value="ids", required=false) List<Long> ids) {
		return ResponseEvent.unwrap(service.getReceiptMatrix(cpId, visitId, ids));
	}
	@PutMapping("/receive") @ResponseBody
	public List<Long> receive(@RequestBody List<SpecimenReceiptInput> specimens) { return ResponseEvent.unwrap(service.receive(specimens)); }
}
