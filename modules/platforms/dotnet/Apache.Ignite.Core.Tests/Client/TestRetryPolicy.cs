/*
 * Copyright 2021 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace Apache.Ignite.Core.Tests.Client
{
    using System.Collections.Generic;
    using System.Linq;
    using Apache.Ignite.Core.Client;

    /// <summary>
    /// Test policy.
    /// </summary>
    public class TestRetryPolicy : IClientRetryPolicy
    {
        /** */
        private readonly IReadOnlyCollection<ClientOperationType> _allowedOperations;

        /** */
        private readonly List<IClientRetryPolicyContext> _invocations = new List<IClientRetryPolicyContext>();

        /// <summary>
        /// Initializes a new instance of <see cref="TestRetryPolicy"/> class.
        /// </summary>
        /// <param name="allowedOperations">A list of operation types to retry.</param>
        public TestRetryPolicy(params ClientOperationType[] allowedOperations)
        {
            _allowedOperations = allowedOperations.ToArray();
        }

        /// <summary>
        /// Gets the invocations.
        /// </summary>
        public IReadOnlyList<IClientRetryPolicyContext> Invocations => _invocations;

        /** <inheritDoc /> */
        public bool ShouldRetry(IClientRetryPolicyContext context)
        {
            _invocations.Add(context);

            return _allowedOperations.Contains(context.Operation);
        }
    }
}
