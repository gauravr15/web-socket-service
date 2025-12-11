package com.odin.web_socket_service.constants;

public class ApplicationConstants {

	public static final String API_VERSION = "/v1";
	public static final String SIGN_UP = "/signUp";
	public static final String APP_LANG = "appLang";
	public static final String SIGN_IN = "/signIn";
	public static final String DETAILS = "/details";
	public static final String CUSTOMER = "/customer";
	public static final String UPDATE = "/update";
	public static final String FCM_NOTIFICATION_TOKEN = "/fcmNotificationToken";
	public static final String SAVE = "/save";
	public static final String OTP = "otp";
	public static final String GENERATE_OTP = "/generate-otp";

	public static final String AUTH_FLOW_SIGNUP = "auth:flow:signup";
	public static final String AUTH_FLOW_SIGNIN = "auth:flow:signin";
	public static final String PASSWORD_BASED_AUTH = "PASSWORD_BASED_AUTH";
	public static final String OTP_BASED_AUTH = "OTP_BASED_AUTH";
	public static final String BULK = "/bulk";
	public static final String UPLOAD = "/upload";
	public static final String FILES = "/files";
	public static final String DOWNLOAD = "/download";
	public static final String CHECK_AVAILABLE_FILES = "/check-availability";
	
	// Kafka Topics
	public static final String KAFKA_UNDELIVERED_NOTIFICATION_TOPIC = "undelivered.notification.message";
	
	// Kafka Key Prefixes
	public static final String KAFKA_UNDELIVERED_MESSAGE_KEY_PREFIX = "undelivered:";
	
	// Notification related constants
	public static final long DEFAULT_NOTIFICATION_ID = 1L;
	public static final String NOTIFICATION_MAP_RECEIVER_MOBILE = "receiverMobile";
	public static final String NOTIFICATION_MAP_SENDER_MOBILE = "senderMobile";
	public static final String NOTIFICATION_MAP_SENDER_CUSTOMER_ID = "senderCustomerId";
	public static final String NOTIFICATION_MAP_MESSAGE = "message";
	
	// Message type constants
	public static final String MESSAGE_TYPE_CHAT = "chat";
	public static final String MESSAGE_TYPE_CALL_OFFER = "call-offer";
	public static final String MESSAGE_TYPE_CALL_ANSWER = "call-answer";
	public static final String MESSAGE_TYPE_ICE = "ice";
	public static final String MESSAGE_TYPE_CALL_END = "call-end";
	
	// Generic message for non-text notifications
	public static final String GENERIC_FILE_MESSAGE = "Sent a file";
	
}
