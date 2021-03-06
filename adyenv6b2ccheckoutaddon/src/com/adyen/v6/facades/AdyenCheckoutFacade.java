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

import java.security.SignatureException;
import java.util.Map;
import java.util.SortedMap;
import javax.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import com.adyen.v6.forms.AdyenPaymentForm;
import de.hybris.platform.commercefacades.order.data.CartData;
import de.hybris.platform.commercefacades.order.data.OrderData;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.payment.PaymentInfoModel;
import de.hybris.platform.order.InvalidCartException;

/**
 * Adyen Checkout Facade for initiating payments using CC or APM
 */
public interface AdyenCheckoutFacade {
    /**
     * Validates an HPP response
     *
     * @param hppResponseData map with hpp data
     * @param merchantSig     merchant signature
     * @throws SignatureException in case signature doesn't match
     */
    void validateHPPResponse(SortedMap<String, String> hppResponseData, String merchantSig) throws SignatureException;

    /**
     * Retrieve the CSE JS Url
     */
    String getCSEUrl();

    /**
     * Retrieve the HPP base URL for the current basestore
     *
     * @return HPP url
     */
    String getHppUrl();

    /**
     * Removes cart from the session so that users can't update it while being in a payment page
     */
    void lockSessionCart();

    /**
     * Restores the sessionCart that has been previously locked
     *
     * @return session cart
     * @throws InvalidCartException if cart cannot be retrieved
     */
    CartModel restoreSessionCart() throws InvalidCartException;

    /**
     * Handles an HPP response
     * In case of authorized, it places an order from cart
     *
     * @param request Request object containing HPP data
     * @return OrderData
     * @throws SignatureException if signature doesn't match
     */
    OrderData handleHPPResponse(HttpServletRequest request) throws SignatureException;

    @Deprecated
    OrderData authoriseCardPayment(HttpServletRequest request, CartData cartData) throws Exception;

    /**
     * Authorizes a payment using Adyen API
     * In case of authorized, it places an order from cart
     *
     * @param request  HTTP Request object
     * @param cartData cartData object
     * @return OrderData
     * @throws Exception In case order failed to be created
     */
    OrderData authorisePayment(HttpServletRequest request, CartData cartData) throws Exception;

    /**
     * Handles an 3D response
     * In case of authorized, it places an order from cart
     *
     * @param request HTTP Request object
     * @return OrderData
     * @throws Exception In case order failed to be created
     */
    OrderData handle3DResponse(HttpServletRequest request) throws Exception;

    /**
     * Initializes an HPP payment
     * Returns map of data to be submitted to Adyen HPP
     *
     * @param cartData    Shopper's cart
     * @param redirectUrl HPP result url
     * @return HPP data
     * @throws SignatureException In case signature cannot be generated
     */
    Map<String, String> initializeHostedPayment(CartData cartData, String redirectUrl) throws SignatureException;

    /**
     * Retrieve available payment methods
     */
    void initializeCheckoutData(Model model);

    /**
     * Returns whether Boleto should be shown as an available payment method on the checkout page
     * Relevant for Brasil
     */
    boolean showBoleto();

    /**
     * Returns whether CC can be stored depending on the recurring contract settings
     */
    boolean showRememberDetails();

    /**
     * Returns whether Social Security Number should be shown on the checkout page
     * Relevant for openinvoice methods
     */
    boolean showSocialSecurityNumber();

    /**
     * Creates PaymentInfoModel based on cart and form data
     */
    PaymentInfoModel createPaymentInfo(CartModel cartModel, AdyenPaymentForm adyenPaymentForm);

    /**
     * Handles payment form submission
     * Validates the form and updates the cart based on form data
     * Updates BindingResult
     */
    void handlePaymentForm(AdyenPaymentForm adyenPaymentForm, BindingResult bindingResult);
}
