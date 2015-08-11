;; Copyright © 2015, JUXT LTD.

(ns ^{:doc "HTTP content negotiation"}
  yada.negotiation
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :refer :all :exclude [trace]]
   [schema.core :as s]
   [yada.charset :as cs]
   [yada.coerce :refer (to-list to-set)]
   [yada.mime :as mime]
   [yada.util :refer (http-token parse-csv)])
  (:import [yada.charset CharsetMap]
           [yada.mime MediaTypeMap]))

;; Consider either moving this into yada.representation, or promote as a
;; separate library.

;; ------------------------------------------------------------------------
;; Content types

(defn- content-type-acceptable?
  "Compare a single acceptable mime-type (extracted from an Accept
  header) and a candidate. If the candidate is acceptable, return a
  sortable vector [acceptable candidate quality1 quality2]. Quality1
  prefers specificity, e.g. prefers text/html over text/* over
  */*. Quality2 gives preference to candidates with a greater number of
  parameters, which preferes text/html;level=1 over text/html. This
  meets the criteria in the HTTP specifications. Although the preference
  that should result with multiple parameters is not specified formally,
  candidates that have a greater number of parameters are preferred."
  [acceptable candidate]
  (when
      (= (:parameters acceptable)
         (:parameters candidate)
         #_(select-keys (:parameters candidate) (keys (:parameters acceptable))))
    (cond
      (and (= (:type acceptable) (:type candidate))
           (= (:subtype acceptable) (:subtype candidate)))
      [acceptable candidate {:quality 3} {:quality (count (:parameters candidate))}]

      (and (= (:type acceptable) (:type candidate))
           (= (:subtype acceptable) "*"))
      [acceptable candidate {:quality 2} {:quality (count (:parameters candidate))}]

      (and (= (mime/media-type acceptable) "*/*"))
      [acceptable candidate {:quality 1} {:quality (count (:parameters candidate))}])))

(defn- any-content-type-acceptable? [acceptables candidate]
  (some #(content-type-acceptable? % candidate) acceptables))

(defn- negotiate-content-type*
  "Return the best content type via negotiation."
  [acceptables candidates]
  (->> candidates
       (keep (partial any-content-type-acceptable? acceptables))
       (sort-by #(vec (map :quality %)))
       reverse ;; highest quality wins
       first ;; winning pair
       second ;; extract the server provided mime-type
       ;; Commented because the caller needs this in order to sort now
       ;; (#(dissoc % :quality))
       ))

(defn negotiate-content-type [accept-header available]
  (negotiate-content-type*
   (map mime/string->media-type (parse-csv accept-header))
   available))

;; ------------------------------------------------------------------------
;; Charsets

(defn- acceptable-charset? [acceptable-charset candidate]
  (when
      (or (= (cs/charset acceptable-charset) "*")
          (and
           (some? (cs/charset acceptable-charset))
           (= (cs/charset acceptable-charset)
              (cs/charset candidate)))
          ;; Finally, let's see if their canonical names match
          (and
           (some? (cs/canonical-name acceptable-charset))
           (= (cs/canonical-name acceptable-charset)
              (cs/canonical-name candidate))))
    [acceptable-charset candidate]))

(defn- any-charset-acceptable? [acceptables candidate]
  (if (nil? acceptables)
    [candidate candidate] ; no header means the user-agent accepts 'any charset' in response - rfc7231.html#section-5.3.3
    (some #(acceptable-charset? % candidate) acceptables)))

(defn- negotiate-charset*
  "Returns a pair. The first is the charset alias used in the Accept
  header by the user-agent, the second is the charset alias declared by
  the server. Often these are the same, but if they differ, use the
  first alias when talking with the user-agent, while using the second
  alias while asking the resource/service to encode the representation"
  [acceptables candidates]
  (let [winner
        (->> candidates
             (keep (partial any-charset-acceptable? acceptables))
             (sort-by #(vec (map :quality %)))
             reverse ;; highest quality wins
             first ;; winning pair
             )]
    (when winner
      (let [cs1 (-> winner first cs/charset)
            cs2 (-> winner second cs/charset)]
        (if (not= cs1 "*")
          ;; We return a pair. The first is what we set the charset
          ;; parameter of the Content-Type header. The second is what we
          ;; ask the server to provide. These could be different, because
          ;; the user-agent and server may be using different aliases for
          ;; the same charset.
          [cs1 cs2]
          ;; Otherwise, the server gets to dictate the charset
          [cs2 cs2]
          )))))

(defn negotiate-charset [accept-charset-header candidates]
  (negotiate-charset*
   (when accept-charset-header
     (map cs/to-charset-map (parse-csv accept-charset-header)))
   (map cs/to-charset-map candidates)))

(defn parse-encoding [s]
  (let [[_ encoding q]
        (re-matches (re-pattern (str "("  "(?:" http-token "|\\*)" ")(?:(?:;q=)(" http-token "))?"))
                    s)]
    {:encoding encoding
     :quality (if q (try
                     (Float/parseFloat q)
                     (catch java.lang.NumberFormatException e
                       1.0))
                 1.0)}))

;; ------------------------------------------------------------------------
;; Encodings

(defn negotiate-encoding [accept-encoding-header candidates]
  (when accept-encoding-header
    (let [acceptable-encodings (map parse-encoding (parse-csv accept-encoding-header))]
      ;; TODO
      )))

;; ------------------------------------------------------------------------
;; Languages

(defn negotiate-language [accept-language-header candidates]
  (when candidates
    (if accept-language-header
      (java.util.Locale/lookupTag (java.util.Locale$LanguageRange/parse accept-language-header) (seq candidates))
      (first candidates))))

;; ------------------------------------------------------------------------
;; Unified negotiation

(s/defschema RequestInfo
  "Captures all the aspects of a request relevant to
  content-negotation. Used to make testing easier. Could be replaced
  eventually with the original Ring request."
  {:method s/Keyword
   (s/optional-key :accept) s/Str          ; Accept header value
   (s/optional-key :accept-charset) s/Str  ; Accept-Charset header value
   (s/optional-key :accept-encoding) s/Str ; Accept-Encoding header value
   (s/optional-key :accept-language) s/Str ; Accept-Language header value
   })

(s/defschema NegotiationResult
  "The raw result of a negotiation."
  {:method s/Keyword
   :request RequestInfo
   ;; There is a subtle distinction between a missing entry and a nil
   ;; entry.  If content-type/charset is nil, it means no acceptable
   ;; content-type, whereas if the content-type/charset entry is missing
   ;; it means no content-type/charset is required (no resource
   ;; representation). These differences affect whether a 406 status is
   ;; returned.
   (s/optional-key :content-type) (s/maybe {:type s/Str
                                            :subtype s/Str
                                            :parameters {s/Str s/Str}
                                            :quality s/Num})
   (s/optional-key :charset) (s/maybe (s/pair s/Str "known-by-client" s/Str "known-by-server"))
   (s/optional-key :encoding) (s/maybe s/Str)
   (s/optional-key :language) (s/maybe s/Str)
   })

(s/defschema ServerOffer
  "A map representing a cross-product of potential server
  capabilities. Each entry is optional. Each value is a set, indicating
  a disjunction of possibie values."
  {(s/optional-key :method) #{s/Keyword}
   (s/optional-key :content-type) #{MediaTypeMap}
   (s/optional-key :charset) #{CharsetMap}
   (s/optional-key :encoding) #{s/Str}
   (s/optional-key :language) #{s/Str}})

(defn- merge-content-type [m accept-header content-types accept-charset charsets]
  {:content-type (negotiate-content-type accept-header content-types)
   :charset (negotiate-charset accept-charset charsets)})

(defn- merge-encoding [m accept-encoding encodings]
  (when-let [encoding (negotiate-encoding accept-encoding encodings)]
    (merge m {:encoding encoding})))

(defn- merge-language [m accept-language langs]
  (when-let [lang (negotiate-language accept-language langs)]
    (merge m {:language lang})))

(s/defn acceptable?
  [request :- RequestInfo
   server-offer :- ServerOffer]
  :- NegotiationResult
  ;; If server-offer specifies a set of methods, find a
  ;; match, otherwise match on the request method so that
  ;; server-offer method guards are strictly optional.

  (when-let [method ((or (:method server-offer) identity) (:method request))]
    (cond->
        ;; Start with the values that always appear
        {:method method :request request}

      ;; content-type and charset (could be nil)
      (:content-type server-offer)
      (merge
       (let [cts (:content-type server-offer)]
         (let [content-type (negotiate-content-type (or (:accept request) "*/*") cts)]
           (merge
            {:content-type content-type}
            (when content-type {:charset (negotiate-charset (:accept-charset request) (:charset server-offer))})))))

      (:language server-offer)
      (merge-language (:accept-language request) (:language server-offer))

      (:encoding server-offer)
      (merge-encoding (:accept-encoding request) (:encoding server-offer)))))

(s/defn negotiate
  "Return a sequence of negotiation results, ordered by
  preference (client first, then server). The request and each
  server-offer is presumed to have been pre-validated."
  [request :- RequestInfo
   server-offers :- (s/either #{ServerOffer} [ServerOffer])]
  :- [NegotiationResult]
  (->> server-offers
       (keep (partial acceptable? request))
       (sort-by (juxt (comp :quality :content-type) (comp :charset)
                      ;; TODO: Add encoding and language
                      )
                ;; Trick to get sort-by to reverse sort
                (comp - compare))))

(defn- has-registered-charset-param?
  "Whether the given media-type has a charset parameter. RFC 6657
  recommends that media-types that have the ability to embed the charset
  encoding in the content itself (e.g. application/xml) should not register a
  charset parameter with IANA. That way, there can be no discrepancy
  between the response's Content-Type header and the content
  itself. Since text/html already has an (optional) charset parameter
  registered, and due to very common practice, we don't include
  text/html as one of these types, so that text/html content IS given a
  charset parameter in the response's Content-Type header. Potentially
  this could be configurable in the future."
  [mt]
  (and (= (:type mt) "text")
       ;; See http://tools.ietf.org/html/rfc6657 TODO: This list is not
       ;; very comprehensive, go through 'text/*' IANA registrations
       ;; http://www.iana.org/assignments/media-types/media-types.xhtml
       (not (contains? #{"application/xhtml+xml"
                         "application/xml"} (mime/media-type mt)))))

(s/defn vary [method :- s/Keyword
              server-offers :- [ServerOffer]]
  (let [server-offers (filter #((or (:method %) identity) method) server-offers)
        varies (remove nil?
                       [(when-let [ct (apply set/union (map :content-type server-offers))]
                          (when (> (count ct) 1) :content-type))
                        (when-let [cs (apply set/union (map :charset server-offers))]
                          (when (> (count cs) 1) :charset))
                        (when-let [encodings (apply set/union (map :encoding server-offers))]
                          (when (> (count encodings) 1) :encoding))
                        (when-let [languages (apply set/union (map :language server-offers))]
                          (when (> (count languages) 1) :language))])]
    (when (not-empty varies)
      (set varies))))

(s/defn interpret-negotiation
  "Take a negotiated result and determine status code and message. If
  unacceptable (to the client) content-types yield 406. Unacceptable (to
  the server) content-types yield 415- Unsupported Media Type"
  [{:keys [method content-type charset encoding language request] :as result} :- NegotiationResult]
  :- {(s/optional-key :status) s/Int
      (s/optional-key :message) s/Str
      (s/optional-key :content-type) MediaTypeMap
      (s/optional-key :client-charset) s/Str
      (s/optional-key :server-charset) s/Str
      (s/optional-key :encoding) s/Str
      (s/optional-key :language) s/Str}

  (cond
    (and (contains? result :content-type)
         (nil? content-type))
    {:status 406 :message "Not Acceptable (content-type)"}

    (and (:accept-charset request)
         (contains? result :charset)
         (nil? charset))
    {:status 406 :message "Not Acceptable (charset)"}

    ;; Note: We don't send a 406 if there is no negotiated encoding,
    ;; instead we use the 'identity' encoding, as per the spec.

    (and (:accept-language request)
         (contains? result :language)
         (nil? language))
    {:status 406 :message "Not Acceptable (language)"}

    :otherwise
    (merge
     {}
     (when content-type
       {:content-type
        (if (and charset
                 (has-registered-charset-param? content-type)
                 (not (some-> content-type :parameters (get "charset"))))
          (assoc-in content-type [:parameters "charset"] (first charset))
          content-type)})
     ;; Charsets can be known by aliases, so each party
     ;; is given the exact name that they have
     ;; specified the charset in, rather than the
     ;; canonical name of the charset. A party should
     ;; use the canonical name if possible, but if it
     ;; doesn't, there might be a valid reason why not
     ;; and we should honor its decision.
     (when charset {:client-charset (first charset)
                    :server-charset (second charset)})
     ;; It's true that the spec. says to default to
     ;; 'identity', but this is really the same as not
     ;; negotiating an encoding at all
     (when encoding {:encoding encoding})
     (when language {:language language}))))


(s/defn extract-request-info [req] :- RequestInfo
  (merge {:method (:request-method req)}
         (when-let [header (get-in req [:headers "accept"])]
           {:accept header})
         (when-let [header (get-in req [:headers "accept-charset"])]
           {:accept-charset header})
         (when-let [header (get-in req [:headers "accept-encoding"])]
           {:accept-encoding header})
         (when-let [header (get-in req [:headers "accept-language"])]
           {:accept-language header})))

(defn coerce-representations
  "For performance reasons it is sensible to coerce the representations
  ahead of time, rather than on each request."
  [reps]
  (when reps
    (mapv
     (fn [rep]
       (merge
        (select-keys rep [:method])
        (when-let [ct (:content-type rep)]
          {:content-type (set (map mime/string->media-type (to-set ct)))})
        (when-let [cs (:charset rep)]
          {:charset (set (map cs/to-charset-map (to-set cs)))})

        ;; Check to see if the server-specified charset is
        ;; recognized (registered with IANA). If it isn't we
        ;; throw a 500, as this is a server error. (It might be
        ;; necessary to disable this check in future but a
        ;; balance should be struck between giving the
        ;; developer complete control to dictate charsets, and
        ;; error-proofing. It might be possible to disable
        ;; this check for advanced users if a reasonable case
        ;; is made.)
        #_(when-let [bad-charset
                     (some (fn [mt] (when-let [charset (some-> mt :parameters (get "charset"))]
                                     (when-not (charset/valid-charset? charset) charset)))
                           available-content-types)]
            (throw (ex-info (format "Resource or service declares it produces an unknown charset: %s" bad-charset) {:charset bad-charset})))

        (when-let [enc (:encoding rep)]
          {:encoding (to-set enc)})
        (when-let [langs (:language rep)]
          {:language (to-list langs)})))
     reps)))

;; We need to calculate all the possible representations that a representation supports, this is needed by the ETag implementation
;; pass result of coerce-representations
(defn representation-seq
  "Return a sequence of all possible representations."
  [reps]
  (for [rep reps
        content-type (or (:content-type rep) [nil])
        charset (or (:charset rep) [nil])
        language (or (:language rep) [nil])
        encoding (or (:encoding rep) [nil])]
    (merge
     (when content-type {:content-type content-type})
     (when charset {:charset charset})
     (when language {:language language})
     (when encoding {:encoding encoding}))))

(defn to-vary-header [vary]
  (str/join ", "
            ;; TODO: Is this really about removing nils. Replace with
            ;; keep?
            (filter string? (map {:charset "accept-charset"
                                  :content-type "accept"
                                  :encoding "accept-encoding"
                                  :language "accept-language"}
                                 vary))))



;; TODO: see rfc7231.html#section-3.4.1

;; "including both the explicit
;; negotiation fields of Section 5.3 and implicit characteristics, such
;; as the client's network address or parts of the User-Agent field."

;; selection of representation can be made based on the User-Agent header, IP address, other 'implicit' data in the request, etc., so this needs to be extensible and overrideable

;; "In order to improve the server's guess, a user agent MAY send request header fields that describe its preferences."

;;    "A Vary header field (Section 7.1.4) is often sent in a response
;;    subject to proactive negotiation to indicate what parts of the
;;    request information were used in the selection algorithm."


;; TODO Should also allow re-negotiation for errors, and allow a special type
;; of representations that declares its just for errors, so users can say
;; they can provide content in both text/html and application/csv but
;; errors must be in text/plain.
