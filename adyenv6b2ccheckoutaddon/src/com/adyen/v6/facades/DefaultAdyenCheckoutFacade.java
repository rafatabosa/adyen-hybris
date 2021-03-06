/*
 *                        ######
 *                        ######
 *  ############    ####( ######  #####. ######  ############   ############
 *  #############  #####( ######  #####. ######  #############  #############
 *         ######  #####( ######  #####. ######  #####  ######  #####  ######
 *  ###### ######  #####( ######  #####. ######  #####  #####   #####  ######
 *  ###### ######  #####( ######  #####. ######  #####          #####  ######
 *  #############  #############  #############  #############  #####  ######
 *   ############   ############  #############   ############  #####  ######
 *                                       ######
 *                                #############
 *                                ############
 *
 *  Adyen Hybris Extension
 *
 *  Copyright (c) 2017 Adyen B.V.
 *  This file is open source and available under the MIT license.
 *  See the LICENSE file for more info.
 */
package com.adyen.v6.facades;

import java.io.IOException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.springframework.ui.Model;
import org.springframework.util.Assert;
import org.springframework.validation.BindingResult;
import com.adyen.Util.HMACValidator;
import com.adyen.Util.Util;
import com.adyen.constants.HPPConstants;
import com.adyen.httpclient.HTTPClientException;
import com.adyen.model.Amount;
import com.adyen.model.PaymentResult;
import com.adyen.model.hpp.PaymentMethod;
import com.adyen.model.recurring.Recurring;
import com.adyen.model.recurring.RecurringDetail;
import com.adyen.service.exception.ApiException;
import com.adyen.v6.enums.RecurringContractMode;
import com.adyen.v6.exceptions.AdyenNonAuthorizedPaymentException;
import com.adyen.v6.factory.AdyenPaymentServiceFactory;
import com.adyen.v6.forms.AdyenPaymentForm;
import com.adyen.v6.forms.validation.AdyenPaymentFormValidator;
import com.adyen.v6.repository.OrderRepository;
import com.adyen.v6.service.AdyenOrderService;
import com.adyen.v6.service.AdyenPaymentService;
import com.adyen.v6.service.AdyenTransactionService;
import de.hybris.platform.commercefacades.order.CheckoutFacade;
import de.hybris.platform.commercefacades.order.OrderFacade;
import de.hybris.platform.commercefacades.order.data.CartData;
import de.hybris.platform.commercefacades.order.data.OrderData;
import de.hybris.platform.commercefacades.user.data.AddressData;
import de.hybris.platform.commercefacades.user.data.CountryData;
import de.hybris.platform.commerceservices.strategies.CheckoutCustomerStrategy;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.PaymentInfoModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.order.CartService;
import de.hybris.platform.order.InvalidCartException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.store.BaseStoreModel;
import de.hybris.platform.store.services.BaseStoreService;
import static com.adyen.constants.HPPConstants.Fields.BRAND_CODE;
import static com.adyen.constants.HPPConstants.Fields.COUNTRY_CODE;
import static com.adyen.constants.HPPConstants.Fields.CURRENCY_CODE;
import static com.adyen.constants.HPPConstants.Fields.ISSUER_ID;
import static com.adyen.constants.HPPConstants.Fields.MERCHANT_ACCOUNT;
import static com.adyen.constants.HPPConstants.Fields.MERCHANT_REFERENCE;
import static com.adyen.constants.HPPConstants.Fields.MERCHANT_SIG;
import static com.adyen.constants.HPPConstants.Fields.PAYMENT_AMOUNT;
import static com.adyen.constants.HPPConstants.Fields.RES_URL;
import static com.adyen.constants.HPPConstants.Fields.SESSION_VALIDITY;
import static com.adyen.constants.HPPConstants.Fields.SHIP_BEFORE_DATE;
import static com.adyen.constants.HPPConstants.Fields.SKIN_CODE;
import static com.adyen.v6.constants.Adyenv6coreConstants.OPENINVOICE_METHODS_ALLOW_SOCIAL_SECURITY_NUMBER;
import static com.adyen.v6.constants.Adyenv6coreConstants.OPENINVOICE_METHODS_API;
import static com.adyen.v6.constants.Adyenv6coreConstants.PAYMENT_METHOD_BOLETO;
import static de.hybris.platform.order.impl.DefaultCartService.SESSION_CART_PARAMETER_NAME;

/**
 * Adyen Checkout Facade for initiating payments using CC or APM
 */
public class DefaultAdyenCheckoutFacade implements AdyenCheckoutFacade {
    private BaseStoreService baseStoreService;
    private SessionService sessionService;
    private CartService cartService;
    private OrderFacade orderFacade;
    private CheckoutFacade checkoutFacade;
    private AdyenTransactionService adyenTransactionService;
    private OrderRepository orderRepository;
    private AdyenOrderService adyenOrderService;
    private CheckoutCustomerStrategy checkoutCustomerStrategy;
    private HMACValidator hmacValidator;
    private AdyenPaymentServiceFactory adyenPaymentServiceFactory;
    private ModelService modelService;

    public static final String SESSION_LOCKED_CART = "adyen_cart";
    public static final String SESSION_MD = "adyen_md";
    public static final String THREE_D_MD = "MD";
    public static final String THREE_D_PARES = "PaRes";
    public static final Logger LOGGER = Logger.getLogger(AdyenCheckoutFacade.class);
    public static final String MODEL_SELECTED_PAYMENT_METHOD = "selectedPaymentMethod";
    public static final String MODEL_PAYMENT_METHODS = "paymentMethods";
    public static final String MODEL_ALLOWED_CARDS = "allowedCards";
    public static final String MODEL_REMEMBER_DETAILS = "showRememberTheseDetails";
    public static final String MODEL_STORED_CARDS = "storedCards";
    public static final String MODEL_CSE_URL = "cseUrl";
    public static final String MODEL_DF_URL = "dfUrl";
    public static final String DF_VALUE = "dfValue";
    public static final String MODEL_OPEN_INVOICE_METHODS = "openInvoiceMethods";
    public static final String MODEL_SHOW_SOCIAL_SECURITY_NUMBER = "showSocialSecurityNumber";
    public static final String MODEL_SHOW_BOLETO = "showBoleto";

    public DefaultAdyenCheckoutFacade() {
        hmacValidator = new HMACValidator();
    }

    @Override
    public void validateHPPResponse(SortedMap<String, String> hppResponseData, String merchantSig) throws SignatureException {
        BaseStoreModel baseStore = getBaseStoreService().getCurrentBaseStore();

        String hmacKey = baseStore.getAdyenSkinHMAC();
        Assert.notNull(hmacKey);

        String dataToSign = getHmacValidator().getDataToSign(hppResponseData);
        String calculatedMerchantSig = getHmacValidator().calculateHMAC(dataToSign, hmacKey);
        LOGGER.debug("Calculated signature: " + calculatedMerchantSig);
        if (! calculatedMerchantSig.equals(merchantSig)) {
            LOGGER.error("Signature does not match!");
            throw new SignatureException("Signatures doesn't match");
        }
    }

    @Override
    public String getCSEUrl() {
        BaseStoreModel baseStore = baseStoreService.getCurrentBaseStore();

        String cseId = baseStore.getAdyenCSEID();
        Assert.notNull(cseId);

        return getAdyenPaymentService().getHppEndpoint() + "/cse/js/" + cseId + ".shtml";
    }

    @Override
    public String getHppUrl() {
        return getAdyenPaymentService().getHppEndpoint() + "/details.shtml";
    }

    @Override
    public void lockSessionCart() {
        getSessionService().setAttribute(SESSION_LOCKED_CART, cartService.getSessionCart());
        getSessionService().removeAttribute(SESSION_CART_PARAMETER_NAME);
        //Refresh session
        getCartService().getSessionCart();
    }

    @Override
    public CartModel restoreSessionCart() throws InvalidCartException {
        CartModel cartModel = getSessionService().getAttribute(SESSION_LOCKED_CART);
        if (cartModel == null) {
            throw new InvalidCartException("Cart does not exist!");
        }
        getCartService().setSessionCart(cartModel);

        return cartModel;
    }

    @Override
    public OrderData handleHPPResponse(final HttpServletRequest request) throws SignatureException {
        final SortedMap<String, String> hppResponseData = new TreeMap<>();

        //Compose HPP response data map
        mapRequest(request, hppResponseData, HPPConstants.Response.AUTH_RESULT);
        mapRequest(request, hppResponseData, HPPConstants.Response.MERCHANT_REFERENCE);
        mapRequest(request, hppResponseData, HPPConstants.Response.PAYMENT_METHOD);
        mapRequest(request, hppResponseData, HPPConstants.Response.PSP_REFERENCE);
        mapRequest(request, hppResponseData, HPPConstants.Response.SHOPPER_LOCALE);
        mapRequest(request, hppResponseData, HPPConstants.Response.SKIN_CODE);

        LOGGER.debug("Received HPP response: " + hppResponseData);

        String merchantSig = request.getParameter(HPPConstants.Response.MERCHANT_SIG);
        String merchantReference = request.getParameter(HPPConstants.Response.MERCHANT_REFERENCE);
        String authResult = request.getParameter(HPPConstants.Response.AUTH_RESULT);

        validateHPPResponse(hppResponseData, merchantSig);

        OrderData orderData = null;
        //Restore the cart or find the created order
        try {
            restoreSessionCart();

            if (HPPConstants.Response.AUTH_RESULT_AUTHORISED.equals(authResult) || HPPConstants.Response.AUTH_RESULT_PENDING.equals(authResult)) {
                orderData = getCheckoutFacade().placeOrder();
            }
        } catch (InvalidCartException e) {
            LOGGER.debug(e);
            //Cart does not exist, retrieve order
            orderData = getOrderFacade().getOrderDetailsForCode(merchantReference);
        }

        return orderData;
    }

    @Override
    @Deprecated
    public OrderData authoriseCardPayment(final HttpServletRequest request, final CartData cartData) throws Exception {
        return authorisePayment(request, cartData);
    }

    @Override
    public OrderData authorisePayment(final HttpServletRequest request, final CartData cartData) throws Exception {
        CustomerModel customer = null;
        if (! getCheckoutCustomerStrategy().isAnonymousCheckout()) {
            customer = getCheckoutCustomerStrategy().getCurrentUserForCheckout();
        }

        PaymentResult paymentResult = getAdyenPaymentService().authorise(cartData, request, customer);

        LOGGER.debug("authorization result: " + paymentResult);

        //In case of Authorized: create order and authorize it
        if (paymentResult.isAuthorised()) {
            return createAuthorizedOrder(paymentResult);
        }

        //In case of Received: create order
        if (paymentResult.isReceived()) {
            return createOrderFromPaymentResult(paymentResult);
        }

        //In case of RedirectShopper: Lock cart
        if (paymentResult.isRedirectShopper()) {
            getSessionService().setAttribute(SESSION_MD, paymentResult.getMd());

            lockSessionCart();
        }

        throw new AdyenNonAuthorizedPaymentException(paymentResult);
    }

    @Override
    public OrderData handle3DResponse(final HttpServletRequest request) throws Exception {
        String paRes = request.getParameter(THREE_D_PARES);
        String md = request.getParameter(THREE_D_MD);

        String sessionMd = getSessionService().getAttribute(SESSION_MD);

        try {
            //Check if MD matches in order to avoid authorizing wrong order
            if (sessionMd != null && ! sessionMd.equals(md)) {
                throw new SignatureException("MD does not match!");
            }

            restoreSessionCart();

            PaymentResult paymentResult = getAdyenPaymentService().authorise3D(request, paRes, md);

            if (paymentResult.isAuthorised()) {
                return createAuthorizedOrder(paymentResult);
            }

            throw new AdyenNonAuthorizedPaymentException(paymentResult);
        } catch (ApiException e) {
            LOGGER.error("API Exception " + e.getError());
            throw e;
        }
    }

    @Override
    public Map<String, String> initializeHostedPayment(final CartData cartData, final String redirectUrl) throws SignatureException {
        final String sessionValidity = Util.calculateSessionValidity();
        final SortedMap<String, String> hppFormData = new TreeMap<>();

        BaseStoreModel baseStore = baseStoreService.getCurrentBaseStore();

        String merchantAccount = baseStore.getAdyenMerchantAccount();
        String skinCode = baseStore.getAdyenSkinCode();
        String hmacKey = baseStore.getAdyenSkinHMAC();

        Assert.notNull(merchantAccount);
        Assert.notNull(skinCode);
        Assert.notNull(hmacKey);

        Amount amount = Util.createAmount(cartData.getTotalPrice().getValue(), cartData.getTotalPrice().getCurrencyIso());

        String countryCode = "";
        CountryData deliveryCountry = cartData.getDeliveryAddress().getCountry();
        if (deliveryCountry != null) {
            countryCode = deliveryCountry.getIsocode();
        }

        hppFormData.put(PAYMENT_AMOUNT, String.valueOf(amount.getValue()));
        hppFormData.put(CURRENCY_CODE, cartData.getTotalPrice().getCurrencyIso());
        hppFormData.put(SHIP_BEFORE_DATE, sessionValidity);
        hppFormData.put(MERCHANT_REFERENCE, cartData.getCode());
        hppFormData.put(SKIN_CODE, skinCode);
        hppFormData.put(MERCHANT_ACCOUNT, merchantAccount);
        hppFormData.put(SESSION_VALIDITY, sessionValidity);
        hppFormData.put(BRAND_CODE, cartData.getAdyenPaymentMethod());
        hppFormData.put(ISSUER_ID, cartData.getAdyenIssuerId());
        hppFormData.put(COUNTRY_CODE, countryCode);
        hppFormData.put(RES_URL, redirectUrl);
        hppFormData.put(DF_VALUE, cartData.getAdyenDfValue());

        String dataToSign = getHmacValidator().getDataToSign(hppFormData);
        String merchantSig = getHmacValidator().calculateHMAC(dataToSign, hmacKey);

        hppFormData.put(MERCHANT_SIG, merchantSig);

        //Lock the cart
        lockSessionCart();

        return hppFormData;
    }

    private void mapRequest(final HttpServletRequest request, final Map<String, String> map, String parameterName) {
        String value = request.getParameter(parameterName);
        if (value != null) {
            map.put(parameterName, value);
        }
    }

    /**
     * Create order and authorized TX
     */
    private OrderData createAuthorizedOrder(final PaymentResult paymentResult) throws InvalidCartException {
        final CartModel cartModel = cartService.getSessionCart();
        final String merchantTransactionCode = cartModel.getCode();

        //First save the transactions to the CartModel < AbstractOrderModel
        getAdyenTransactionService().authorizeOrderModel(cartModel, merchantTransactionCode, paymentResult.getPspReference());

        return createOrderFromPaymentResult(paymentResult);
    }

    /**
     * Create order
     */
    private OrderData createOrderFromPaymentResult(final PaymentResult paymentResult) throws InvalidCartException {
        LOGGER.debug("Create order from payment result: " + paymentResult.getPspReference());

        OrderData orderData = getCheckoutFacade().placeOrder();
        OrderModel orderModel = orderRepository.getOrderModel(orderData.getCode());
        updateOrder(orderModel, paymentResult);

        return orderData;
    }

    private void updateOrder(final OrderModel orderModel, final PaymentResult paymentResult) {
        try {
            adyenOrderService.updateOrderFromPaymentResult(orderModel, paymentResult);
        } catch (Exception e) {
            LOGGER.error(e);
        }
    }

    @Override
    public void initializeCheckoutData(Model model) {
        final CartData cartData = getCheckoutFacade().getCheckoutCart();
        AdyenPaymentService adyenPaymentService = getAdyenPaymentService();

        //Set APMs from Adyen HPP Directory Lookup
        List<PaymentMethod> alternativePaymentMethods = new ArrayList<>();
        try {
            alternativePaymentMethods = adyenPaymentService.getPaymentMethods(cartData.getTotalPrice().getValue(),
                                                                              cartData.getTotalPrice().getCurrencyIso(),
                                                                              cartData.getDeliveryAddress().getCountry().getIsocode());

            //Exclude cards and boleto
            alternativePaymentMethods = alternativePaymentMethods.stream()
                                                                 .filter(paymentMethod -> ! paymentMethod.getBrandCode().isEmpty()
                                                                         && ! paymentMethod.isCard()
                                                                         && paymentMethod.getBrandCode().indexOf(PAYMENT_METHOD_BOLETO) != 0)
                                                                 .collect(Collectors.toList());
        } catch (HTTPClientException | SignatureException | IOException e) {
            LOGGER.error(ExceptionUtils.getStackTrace(e));
        }

        //Set allowed cards from BaseStore configuration
        BaseStoreModel baseStore = baseStoreService.getCurrentBaseStore();

        List<RecurringDetail> storedCards = new ArrayList<>();
        boolean showRememberTheseDetails = showRememberDetails();
        if (showRememberTheseDetails) {
            //Include stored cards
            CustomerModel customerModel = getCheckoutCustomerStrategy().getCurrentUserForCheckout();
            try {
                storedCards = adyenPaymentService.getStoredCards(customerModel.getCustomerID());
            } catch (ApiException e) {
                LOGGER.error("API Exception " + e.getError());
            } catch (Exception e) {
                LOGGER.error(ExceptionUtils.getStackTrace(e));
            }
        }

        // current selected PaymentMethod
        model.addAttribute(MODEL_SELECTED_PAYMENT_METHOD, cartData.getAdyenPaymentMethod());

        //Set HPP payment methods
        model.addAttribute(MODEL_PAYMENT_METHODS, alternativePaymentMethods);

        //Set allowed Credit Cards
        model.addAttribute(MODEL_ALLOWED_CARDS, baseStore.getAdyenAllowedCards());

        model.addAttribute(MODEL_REMEMBER_DETAILS, showRememberTheseDetails);
        model.addAttribute(MODEL_STORED_CARDS, storedCards);

        //Set the url for CSE script
        model.addAttribute(MODEL_CSE_URL, getCSEUrl());
        model.addAttribute(MODEL_DF_URL, adyenPaymentService.getDeviceFingerprintUrl());

        Set<String> recurringDetailReferences = storedCards.stream().map(RecurringDetail::getRecurringDetailReference).collect(Collectors.toSet());

        //Set stored cards to model
        CartModel cartModel = cartService.getSessionCart();
        cartModel.setAdyenStoredCards(recurringDetailReferences);

        // OpenInvoice Methods
        List<String> openInvoiceMethods = OPENINVOICE_METHODS_API;
        model.addAttribute(MODEL_OPEN_INVOICE_METHODS, openInvoiceMethods);

        // retrieve shipping Country to define if social security number needs to be shown or date of birth field for openinvoice methods
        model.addAttribute(MODEL_SHOW_SOCIAL_SECURITY_NUMBER, showSocialSecurityNumber());

        //Include Boleto banks
        model.addAttribute(MODEL_SHOW_BOLETO, showBoleto());

        modelService.save(cartModel);
    }

    @Override
    public boolean showBoleto() {
        BaseStoreModel baseStore = baseStoreService.getCurrentBaseStore();
        //Check base store settings
        if (baseStore.getAdyenBoleto() == null || ! baseStore.getAdyenBoleto()) {
            return false;
        }

        CartData cartData = getCheckoutFacade().getCheckoutCart();
        String currency = cartData.getTotalPrice().getCurrencyIso();
        String country = cartData.getDeliveryAddress().getCountry().getIsocode();

        //Show only on Brasil with BRL
        return "BRL".equals(currency) && "BR".equals(country);
    }

    @Override
    public boolean showRememberDetails() {
        BaseStoreModel baseStore = baseStoreService.getCurrentBaseStore();

        /*
         * The show remember me checkout should only be shown as the
         * user is logged in and the recurirng mode is set to ONECLICK or ONECLICK,RECURRING
         */
        RecurringContractMode recurringContractMode = baseStore.getAdyenRecurringContractMode();
        if (! getCheckoutCustomerStrategy().isAnonymousCheckout()) {
            if (Recurring.ContractEnum.ONECLICK_RECURRING.name().equals(recurringContractMode.getCode()) || Recurring.ContractEnum.ONECLICK.name().equals(recurringContractMode.getCode())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean showSocialSecurityNumber() {
        Boolean showSocialSecurityNumber = false;
        final AddressData addressData = getCheckoutFacade().getCheckoutCart().getDeliveryAddress();
        String countryCode = addressData.getCountry().getIsocode();
        if (OPENINVOICE_METHODS_ALLOW_SOCIAL_SECURITY_NUMBER.contains(countryCode)) {
            showSocialSecurityNumber = true;
        }
        return showSocialSecurityNumber;
    }

    @Override
    public PaymentInfoModel createPaymentInfo(final CartModel cartModel, AdyenPaymentForm adyenPaymentForm) {
        final PaymentInfoModel paymentInfo = modelService.create(PaymentInfoModel.class);
        paymentInfo.setUser(cartModel.getUser());
        paymentInfo.setSaved(false);
        paymentInfo.setCode(generateCcPaymentInfoCode(cartModel));

        // Clone DeliveryAdress to BillingAddress
        final AddressModel clonedAddress = modelService.clone(cartModel.getDeliveryAddress());
        clonedAddress.setBillingAddress(true);
        clonedAddress.setOwner(paymentInfo);
        paymentInfo.setBillingAddress(clonedAddress);

        paymentInfo.setAdyenPaymentMethod(adyenPaymentForm.getPaymentMethod());
        paymentInfo.setAdyenIssuerId(adyenPaymentForm.getIssuerId());

        paymentInfo.setAdyenRememberTheseDetails(adyenPaymentForm.getRememberTheseDetails());
        paymentInfo.setAdyenSelectedReference(adyenPaymentForm.getSelectedReference());

        // openinvoice fields
        paymentInfo.setAdyenDob(adyenPaymentForm.getDob());

        paymentInfo.setAdyenSocialSecurityNumber(adyenPaymentForm.getSocialSecurityNumber());

        // Boleto fields
        paymentInfo.setAdyenFirstName(adyenPaymentForm.getFirstName());
        paymentInfo.setAdyenLastName(adyenPaymentForm.getLastName());

        modelService.save(paymentInfo);

        return paymentInfo;
    }

    @Override
    public void handlePaymentForm(AdyenPaymentForm adyenPaymentForm, BindingResult bindingResult) {
        //Validate form
        CartModel cartModel = cartService.getSessionCart();
        boolean showRememberDetails = showRememberDetails();
        boolean showSocialSecurityNumber = showSocialSecurityNumber();

        AdyenPaymentFormValidator adyenPaymentFormValidator = new AdyenPaymentFormValidator(cartModel.getAdyenStoredCards(), showRememberDetails, showSocialSecurityNumber);
        adyenPaymentFormValidator.validate(adyenPaymentForm, bindingResult);

        if (bindingResult.hasErrors()) {
            return;
        }

        //Update CartModel
        cartModel.setAdyenCseToken(adyenPaymentForm.getCseToken());
        cartModel.setAdyenDfValue(adyenPaymentForm.getDfValue());

        //Create payment info
        PaymentInfoModel paymentInfo = createPaymentInfo(cartModel, adyenPaymentForm);
        cartModel.setPaymentInfo(paymentInfo);
        modelService.save(cartModel);
    }

    protected String generateCcPaymentInfoCode(final CartModel cartModel) {
        return cartModel.getCode() + "_" + UUID.randomUUID();
    }

    public AdyenPaymentService getAdyenPaymentService() {
        return adyenPaymentServiceFactory.createFromBaseStore(baseStoreService.getCurrentBaseStore());
    }

    public BaseStoreService getBaseStoreService() {
        return baseStoreService;
    }

    public void setBaseStoreService(BaseStoreService baseStoreService) {
        this.baseStoreService = baseStoreService;
    }

    public SessionService getSessionService() {
        return sessionService;
    }

    public void setSessionService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public CartService getCartService() {
        return cartService;
    }

    public void setCartService(CartService cartService) {
        this.cartService = cartService;
    }

    public OrderFacade getOrderFacade() {
        return orderFacade;
    }

    public void setOrderFacade(OrderFacade orderFacade) {
        this.orderFacade = orderFacade;
    }

    public CheckoutFacade getCheckoutFacade() {
        return checkoutFacade;
    }

    public void setCheckoutFacade(CheckoutFacade checkoutFacade) {
        this.checkoutFacade = checkoutFacade;
    }

    public AdyenTransactionService getAdyenTransactionService() {
        return adyenTransactionService;
    }

    public void setAdyenTransactionService(AdyenTransactionService adyenTransactionService) {
        this.adyenTransactionService = adyenTransactionService;
    }

    public OrderRepository getOrderRepository() {
        return orderRepository;
    }

    public void setOrderRepository(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public AdyenOrderService getAdyenOrderService() {
        return adyenOrderService;
    }

    public void setAdyenOrderService(AdyenOrderService adyenOrderService) {
        this.adyenOrderService = adyenOrderService;
    }

    public CheckoutCustomerStrategy getCheckoutCustomerStrategy() {
        return checkoutCustomerStrategy;
    }

    public void setCheckoutCustomerStrategy(CheckoutCustomerStrategy checkoutCustomerStrategy) {
        this.checkoutCustomerStrategy = checkoutCustomerStrategy;
    }

    public HMACValidator getHmacValidator() {
        return hmacValidator;
    }

    public void setHmacValidator(HMACValidator hmacValidator) {
        this.hmacValidator = hmacValidator;
    }

    public AdyenPaymentServiceFactory getAdyenPaymentServiceFactory() {
        return adyenPaymentServiceFactory;
    }

    public void setAdyenPaymentServiceFactory(AdyenPaymentServiceFactory adyenPaymentServiceFactory) {
        this.adyenPaymentServiceFactory = adyenPaymentServiceFactory;
    }

    public ModelService getModelService() {
        return modelService;
    }

    public void setModelService(ModelService modelService) {
        this.modelService = modelService;
    }
}