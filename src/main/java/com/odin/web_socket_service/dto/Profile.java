package com.odin.web_socket_service.dto;

import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import om.odin.web_socket_service.enums.CustomerType;

@Data
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class Profile {

	private Integer customerId;

	private String parentCustomerId;

	private Timestamp creationTimestamp;

	private Timestamp updateTimestamp;

	private String mobile;

	private String email;

	private String firstName;

	private String lastName;

	private String fathersName;

	private CustomerType customerType;

	private String customerSubType;

	private String idType;

	private String idNum;

	private String address;

	private Boolean isActive;

	private Boolean isDeleted;

	private Boolean isNotificationEnabled;

	private Boolean isTransactionEnabled;

	private String bankDetails;

	private Auth auth;

}
