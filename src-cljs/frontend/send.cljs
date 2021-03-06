(ns frontend.send
  (:require [cljs-time.coerce :as time-coerce]
            [cljs-time.core :as time]
            [cljs.core.async :refer [chan]]
            [clojure.spec :as s :include-macros true]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]
            [frontend.api :as api]
            [frontend.utils.vcs-url :as vcs-url]
            [om.next :as om-next]
            [om.util :as om-util])
  (:require-macros [cljs.core.async.macros :as am :refer [go-loop]]))

;; These spec should find a better place to live, but data generation in `send`
;; is all they're used for currently, they can live here for now.
(s/def :organization/vcs-type #{"github" "bitbucket"})
(s/def :organization/name string?)
(s/def :organization/entity (s/keys :req [:organization/vcs-type :organization/name]))

(s/def :project/name string?)
(s/def :project/organization :organization/entity)
(s/def :project/entity (s/keys :req [:project/name :project/organization]))

(s/def :workflow/id uuid?)
(s/def :workflow/name string?)
(s/def :workflow/runs (s/every :run/entity))
(s/def :workflow/project :project/entity)
(s/def :workflow/entity (s/keys :req [:workflow/id
                                      :workflow/name
                                      :workflow/runs
                                      :workflow/project]))

(s/def :run/id uuid?)
(s/def :run/status #{:run-status/waiting
                     :run-status/running
                     :run-status/succeeded
                     :run-status/failed
                     :run-status/canceled})
(s/def :run/started-at (s/nilable inst?))
(s/def :run/stopped-at (s/nilable inst?))
(s/def :run/branch-name string?)
(s/def :run/commit-sha (s/with-gen
                         (s/and string?
                                #(= 40 (count %))
                                (partial re-matches #"^[0-9a-f]*$"))
                         #(gen/fmap string/join
                                    (gen/vector
                                     (gen/fmap char
                                               (gen/one-of [(gen/choose 48 57)
                                                            (gen/choose 97 102)]))
                                     40))))
(s/def :run/job-runs (s/every :job-run/entity))

(s/def :run/entity (s/and
                    (s/keys :req [:run/id
                                  :run/status
                                  :run/started-at
                                  :run/stopped-at
                                  :run/branch-name
                                  :run/commit-sha
                                  :run/job-runs])
                    (s/or
                     :waiting
                     (s/and
                      #(= :run-status/waiting (:run/status %))
                      #(nil? (:run/started-at %))
                      #(nil? (:run/stopped-at %)))

                     :running
                     (s/and
                      #(= :run-status/running (:run/status %))
                      #(:run/started-at %)
                      #(nil? (:run/stopped-at %)))

                     :finished
                     (s/and
                      #(#{:run-status/succeeded
                          :run-status/failed
                          :run-status/canceled}
                        (:run/status %))
                      #(:run/started-at %)
                      #(:run/stopped-at %)
                      #(< (:run/started-at %)
                          (:run/stopped-at %))))))

(s/def :job/id uuid?)
(s/def :job/name string?)
(s/def :job/entity (s/keys :req [:job/id
                                 :job/name]))

(s/def :job-run/id uuid?)
(s/def :job-run/status #{:job-run-status/waiting
                         :job-run-status/running
                         :job-run-status/succeeded
                         :job-run-status/failed
                         :job-run-status/canceled})
(s/def :job-run/started-at (s/nilable inst?))
(s/def :job-run/stopped-at (s/nilable inst?))
(s/def :job-run/job :job/entity)
(s/def :job-run/entity (s/and (s/keys :req [:job-run/id
                                            :job-run/status
                                            :job-run/started-at
                                            :job-run/stopped-at
                                            :job-run/job])
                              (s/or
                               :waiting
                               (s/and
                                #(= :job-run-status/waiting (:job-run/status %))
                                #(nil? (:job-run/started-at %))
                                #(nil? (:job-run/stopped-at %)))

                               :job-running
                               (s/and
                                #(= :job-run-status/job-running (:job-run/status %))
                                #(:job-run/started-at %)
                                #(nil? (:job-run/stopped-at %)))

                               :finished
                               (s/and
                                #(#{:job-run-status/succeeded
                                    :job-run-status/failed
                                    :job-run-status/canceled}
                                  (:job-run/status %))
                                #(:job-run/started-at %)
                                #(:job-run/stopped-at %)
                                #(< (:job-run/started-at %)
                                    (:job-run/stopped-at %))))))


;; via https://github.com/paraseba/faker/blob/master/srfaker/lorem_data.clj
(def latin-word
  (gen/elements
   ["alias" "consequatur" "aut" "perferendis" "sit" "voluptatem" "accusantium" "doloremque" "aperiam" "eaque" "ipsa" "quae" "ab" "illo" "inventore" "veritatis" "et" "quasi" "architecto" "beatae" "vitae" "dicta" "sunt" "explicabo" "aspernatur" "aut" "odit" "aut" "fugit" "sed" "quia" "consequuntur" "magni" "dolores" "eos" "qui" "ratione" "voluptatem" "sequi" "nesciunt" "neque" "dolorem" "ipsum" "quia" "dolor" "sit" "amet" "consectetur" "adipisci" "velit" "sed" "quia" "non" "numquam" "eius" "modi" "tempora" "incidunt" "ut" "labore" "et" "dolore" "magnam" "aliquam" "quaerat" "voluptatem" "ut" "enim" "ad" "minima" "veniam" "quis" "nostrum" "exercitationem" "ullam" "corporis" "nemo" "enim" "ipsam" "voluptatem" "quia" "voluptas" "sit" "suscipit" "laboriosam" "nisi" "ut" "aliquid" "ex" "ea" "commodi" "consequatur" "quis" "autem" "vel" "eum" "iure" "reprehenderit" "qui" "in" "ea" "voluptate" "velit" "esse" "quam" "nihil" "molestiae" "et" "iusto" "odio" "dignissimos" "ducimus" "qui" "blanditiis" "praesentium" "laudantium" "totam" "rem" "voluptatum" "deleniti" "atque" "corrupti" "quos" "dolores" "et" "quas" "molestias" "excepturi" "sint" "occaecati" "cupiditate" "non" "provident" "sed" "ut" "perspiciatis" "unde" "omnis" "iste" "natus" "error" "similique" "sunt" "in" "culpa" "qui" "officia" "deserunt" "mollitia" "animi" "id" "est" "laborum" "et" "dolorum" "fuga" "et" "harum" "quidem" "rerum" "facilis" "est" "et" "expedita" "distinctio" "nam" "libero" "tempore" "cum" "soluta" "nobis" "est" "eligendi" "optio" "cumque" "nihil" "impedit" "quo" "porro" "quisquam" "est" "qui" "minus" "id" "quod" "maxime" "placeat" "facere" "possimus" "omnis" "voluptas" "assumenda" "est" "omnis" "dolor" "repellendus" "temporibus" "autem" "quibusdam" "et" "aut" "consequatur" "vel" "illum" "qui" "dolorem" "eum" "fugiat" "quo" "voluptas" "nulla" "pariatur" "at" "vero" "eos" "et" "accusamus" "officiis" "debitis" "aut" "rerum" "necessitatibus" "saepe" "eveniet" "ut" "et" "voluptates" "repudiandae" "sint" "et" "molestiae" "non" "recusandae" "itaque" "earum" "rerum" "hic" "tenetur" "a" "sapiente" "delectus" "ut" "aut" "reiciendis" "voluptatibus" "maiores" "doloribus" "asperiores" "repellat"]))

(def inst-in-last-day
  (s/gen (s/inst-in (time-coerce/to-date (time/minus (time/now) (time/days 1)))
                    (time-coerce/to-date (time/now)))))

(def
  ^{:doc
    "Generator overrides that make nice looking data. Good for mock data
     displays; bad for property testing, which wants to hit edge cases."}
  dummy-data-overrides
  {:run/branch-name #(gen/fmap (partial string/join "-")
                               (gen/vector latin-word 1 7))
   :run/started-at #(gen/one-of [inst-in-last-day (gen/return nil)])
   :run/stopped-at #(gen/one-of [inst-in-last-day (gen/return nil)])
   :job/name #(gen/fmap (partial string/join "-")
                        (gen/vector latin-word 1 2))
   :job-run/started-at #(gen/one-of [inst-in-last-day (gen/return nil)])
   :job-run/stopped-at #(gen/one-of [inst-in-last-day (gen/return nil)])})



(defn- callback-api-chan
  "Returns a channel which can be used with the API functions. Calls cb with the
  response data when the API call succeeds. Ignores failures.

  This is a temporary shim to reuse the old API functions in the Om Next send."
  [cb]
  (let [ch (chan)]
    (go-loop []
      (let [[_ state data] (<! ch)]
        (when (= state :success)
          (cb (:resp data)))
        (when-not (= state :finished)
          (recur))))
    ch))

(defmulti send* key)

;; This implementation is merely a prototype, which does some rudimentary
;; pattern-matching against a few expected cases to decide which APIs to hit. A
;; more rigorous implementation will come later.
(defmethod send* :remote
  [[_ ui-query] cb]
  (let [{:keys [query rewrite]} (om-next/process-roots ui-query)]
    (doseq [expr query]
      (cond
        (= {:app/current-user [{:user/organizations [:organization/name :organization/vcs-type :organization/avatar-url]}]}
           expr)
        (let [ch (callback-api-chan
                  #(let [orgs (for [api-org %]
                                {:organization/name (:login api-org)
                                 :organization/vcs-type (:vcs_type api-org)
                                 :organization/avatar-url (:avatar_url api-org)})]
                     (cb (rewrite {:app/current-user {:user/organizations (vec orgs)}}) ui-query)))]
          (api/get-orgs ch :include-user? true))

        (and (om-util/ident? (om-util/join-key expr))
             (= :organization/by-vcs-type-and-name (first (om-util/join-key expr)))
             (= '[:organization/vcs-type
                  :organization/name
                  {:organization/projects [:project/vcs-url
                                           :project/name
                                           :project/parallelism
                                           :project/oss?
                                           {:project/followers []}]}
                  {:organization/plan [*]}]
                (om-util/join-value expr)))
        (let [{:keys [organization/vcs-type organization/name]} (second (om-util/join-key expr))]
          (api/get-org-settings
           name vcs-type
           (callback-api-chan
            #(let [projects (for [p (:projects %)]
                              {:project/vcs-url (:vcs_url p)
                               :project/name (vcs-url/repo-name (:vcs_url p))
                               :project/parallelism (:parallel p)
                               ;; Sometimes the backend returns a map of feature_flags,
                               ;; and sometimes it returns :oss directly on the project.
                               :project/oss? (or (:oss p)
                                                 (get-in p [:feature_flags :oss]))
                               :project/followers (vec (for [u (:followers p)]
                                                         {}))})
                   org {:organization/name name
                        :organization/vcs-type vcs-type
                        :organization/projects (vec projects)}]
               (cb (rewrite {(om-util/join-key expr) org}) ui-query))))
          (api/get-org-plan
           name vcs-type
           (callback-api-chan
            #(cb (rewrite {(om-util/join-key expr) {:organization/name name
                                                    :organization/vcs-type vcs-type
                                                    :organization/plan %}})
                 ui-query))))

        (and (om-util/ident? (om-util/join-key expr))
             (= :organization/by-vcs-type-and-name (first (om-util/join-key expr)))
             (= '[:organization/vcs-type
                  :organization/name]
                (om-util/join-value expr)))
        (let [{:keys [organization/vcs-type organization/name]} (second (om-util/join-key expr))]
          (let [org {:organization/name name
                     :organization/vcs-type vcs-type}]
            (cb (rewrite {(om-util/join-key expr) org}) ui-query)))

        (and (om-util/ident? (om-util/join-key expr))
             (= :project/by-org-and-name (first (om-util/join-key expr)))
             (= '[:project/name]
                (om-util/join-value expr)))
        (let [{:keys [project/name]} (second (om-util/join-key expr))]
          (let [project {:project/name name}]
            (cb (rewrite {(om-util/join-key expr) project}) ui-query)))

        (and (om-util/ident? (om-util/join-key expr))
             (= :workflow/by-org-project-and-name (first (om-util/join-key expr))))
        ;; Generate fake data for now.
        (cb (rewrite {(om-util/join-key expr)
                      (let [ident-vals (second (om-util/join-key expr))]
                        (gen/generate
                         (s/gen :workflow/entity
                                (merge
                                 dummy-data-overrides
                                 {[:workflow/name] #(gen/return (:workflow/name ident-vals))
                                  [:workflow/project :project/name] #(gen/return (:project/name ident-vals))
                                  [:workflow/project :project/organization :organization/name] #(gen/return (:organization/name ident-vals))
                                  [:workflow/project :project/organization :organization/vcs-type] #(gen/return (:organization/vcs-type ident-vals))}))))})
            ui-query)

        (and (om-util/ident? (om-util/join-key expr))
             (= :run/by-id (first (om-util/join-key expr))))
        ;; Generate fake data for now.
        (cb (rewrite {(om-util/join-key expr)
                      (let [id (second (om-util/join-key expr))]
                        (gen/generate
                         (s/gen :run/entity
                                (merge
                                 dummy-data-overrides
                                 {[:run/id] #(gen/return id)}))))})
            ui-query)

        :else (throw (str "No clause found for " (pr-str expr)))))))


(defn send [remotes cb]
  (doseq [remote-entry remotes]
    (send* remote-entry cb)))
