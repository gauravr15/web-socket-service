package com.odin.web_socket_service.repo;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import com.odin.web_socket_service.constants.ApplicationConstants;
import com.odin.web_socket_service.dto.Profile;
import com.odin.web_socket_service.dto.ResponseDTO;
import com.odin.web_socket_service.utility.SearchCriteria;
import com.odin.web_socket_service.utility.Utility;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ProfileRepository {

	@Value("${core.update.url}")
	private String coreUpdateUrl;

	@Autowired
	private Utility utility;

//	public Profile findByMobileOrEmail(String mobile, String email) {
//		List<SearchCriteria> searchCriteriaList = new ArrayList<>();
//		searchCriteriaList.add(new SearchCriteria("mobile", ":", mobile, "OR"));
//		searchCriteriaList.add(new SearchCriteria("email", ":", email, "OR"));
//
//		// Make the REST call using your utility method
//		ResponseDTO response = utility.makeRestCall(
//				coreUpdateUrl + ApplicationConstants.CUSTOMER + ApplicationConstants.DETAILS, searchCriteriaList,
//				HttpMethod.POST, ResponseDTO.class);
//
//		return utility.getAnInstance(response.getData(), Profile.class);
//	}
//
//	public Profile findByMobileOrEmailAndCustomerType(String mobile, String email, String customerType) {
//		List<SearchCriteria> searchCriteriaList = new ArrayList<>();
//		searchCriteriaList.add(new SearchCriteria("mobile", ":", mobile, "OR"));
//		searchCriteriaList.add(new SearchCriteria("email", ":", email, "OR"));
//		searchCriteriaList.add(new SearchCriteria("customerType", ":", customerType, "AND"));
//
//		// Make the REST call using your utility method
//		ResponseDTO response = utility.makeRestCall(
//				coreUpdateUrl + ApplicationConstants.CUSTOMER + ApplicationConstants.DETAILS, searchCriteriaList,
//				HttpMethod.POST, ResponseDTO.class);
//
//		return utility.getAnInstance(response.getData(), Profile.class);
//	}

	public Profile findByCustomerId(String id) {
		List<SearchCriteria> searchCriteriaList = new ArrayList<>();
		searchCriteriaList.add(new SearchCriteria("customerId", ":", id, ""));

		// Make the REST call using your utility method
		ResponseDTO response = utility.makeRestCall(
				coreUpdateUrl + ApplicationConstants.CUSTOMER + ApplicationConstants.DETAILS, searchCriteriaList,
				HttpMethod.POST, ResponseDTO.class);

		return utility.getAnInstance(response.getData(), Profile.class);
	}

	public Profile update(Profile profile) {
		ResponseDTO response = utility.makeRestCall(
				coreUpdateUrl + ApplicationConstants.CUSTOMER + ApplicationConstants.UPDATE, profile, HttpMethod.POST,
				ResponseDTO.class);
		return utility.getAnInstance(response.getData(), Profile.class);
	}

//	public List<Profile> findLikeMobileNumber(List<String> mobiles, boolean isActive) {
//		List<SearchCriteria> searchCriteriaList = new ArrayList<>();
//
//	    // Clean up all mobiles (only digits)
//	    List<String> cleanedMobiles = mobiles.stream()
//	        .map(m -> m.replaceAll("[^0-9]", ""))
//	        .collect(Collectors.toList());
//
//	    // Pass as IN operator (exact match)
//	    searchCriteriaList.add(new SearchCriteria("mobile", "IN", cleanedMobiles, "AND"));
//	    searchCriteriaList.add(new SearchCriteria("isActive", ":", isActive, "AND"));
//
//		ResponseDTO response = utility.makeRestCall(
//				coreUpdateUrl + ApplicationConstants.CUSTOMER + ApplicationConstants.DETAILS, searchCriteriaList,
//				HttpMethod.POST, ResponseDTO.class);
//		return utility.getInstances(response.getData(), Profile.class);
//	}
//
//	public Profile findByMobileNumber(String rawId) {
//		List<SearchCriteria> searchCriteriaList = new ArrayList<>();
//		searchCriteriaList.add(new SearchCriteria("mobile", ":", rawId, ""));
//		searchCriteriaList.add(new SearchCriteria("isActive", ":", true, "AND"));
//
//		// Make the REST call using your utility method
//		ResponseDTO response = utility.makeRestCall(
//				coreUpdateUrl + ApplicationConstants.CUSTOMER + ApplicationConstants.DETAILS, searchCriteriaList,
//				HttpMethod.POST, ResponseDTO.class);
//
//		return utility.getAnInstance(response.getData(), Profile.class);
//	}

}
